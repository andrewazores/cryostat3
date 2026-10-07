/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.discovery;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

import io.cryostat.discovery.KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType;
import io.cryostat.discovery.NodeType.BaseNodeType;
import io.cryostat.security.rbac.RbacConfig;
import io.cryostat.security.rbac.SsarAuthorizer;
import io.cryostat.security.rbac.UserAuthorizer;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.security.PermissionsAllowed;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;

@Path("/api/v5/discovery/tree")
public class Discovery {

    private static final String SYNTHETIC_REALM_NAME = "Cryostat Discovery";
    private static final UUID SYNTHETIC_REALM_ID =
            UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    @Inject Logger logger;
    @Inject UserAuthorizer userAuthorizer;
    @Inject RoutingContext routingContext;
    @Inject RbacConfig rbacConfig;

    @GET
    @PermissionsAllowed(value = "discoverynodes:read", inclusive = true)
    @Operation(summary = "Retrieve the entire discovery tree.")
    public DiscoveryNode get(
            @QueryParam("mergeRealms") @DefaultValue("false") boolean mergeRealms) {
        String rawToken = (String) routingContext.get(SsarAuthorizer.ATTR_RAW_ACCESS_TOKEN);
        logger.debugf(
                "Discovery.get: mergeRealms=%b rbacMode=%s rawToken=%s",
                mergeRealms,
                rbacConfig.mode(),
                StringUtils.isBlank(rawToken)
                        ? "<blank>"
                        : "<present, length=" + rawToken.length() + ">");
        DiscoveryNode universe = DiscoveryNode.getUniverse();
        DiscoveryNode filteredUniverse = filterDiscoveryTree(universe, rawToken);
        if (!mergeRealms) {
            return filteredUniverse;
        }
        return mergeRealms(filteredUniverse);
    }

    /**
     * Returns a copy of the universe with all realm subtrees authorization-filtered.
     *
     * <p>Built-in Realm nodes (ie. those backed by a {@code builtin} {@link DiscoveryPlugin}, such
     * as the Kubernetes API realm created by {@link KubeEndpointSlicesDiscovery} or the Custom
     * Targets realm created by {@link CustomDiscovery}) are shared infrastructure known to every
     * caller and are always included, with {@link #filterNodeDfs} pruning any subtree within them
     * that the caller is not authorized to view.
     *
     * <p>Dynamic plugin Realms (ie. one per registered Cryostat Agent instance) are not
     * unconditionally included: the Realm node itself typically identifies a single agent/target,
     * so exposing an empty husk of such a Realm after its contents are pruned would still leak that
     * agent's existence to an unauthorized caller. These Realms are included only if at least one
     * child survives {@link #filterNodeDfs}'s authorization filtering; otherwise the entire Realm
     * node is omitted.
     *
     * <p>When {@code rawToken} is blank, all children (and therefore all Realms) are included.
     */
    private DiscoveryNode filterDiscoveryTree(DiscoveryNode universe, String rawToken) {
        DiscoveryNode filteredUniverse = copyNode(universe);
        var builtinRealmIds =
                DiscoveryPlugin.find("#DiscoveryPlugin.getBuiltinRealmIds")
                        .project(UUID.class)
                        .list();
        for (DiscoveryNode realm : universe.children) {
            logger.debugf(
                    "Discovery.filterDiscoveryTree: filtering realm '%s' (%d children)",
                    realm.name, realm.children == null ? 0 : realm.children.size());
            DiscoveryNode filteredRealm = filterNodeDfs(realm, rawToken);
            boolean builtin = builtinRealmIds.contains(realm.id);
            if (builtin || !filteredRealm.children.isEmpty()) {
                filteredUniverse.children.add(filteredRealm);
            } else {
                logger.debugf(
                        "Discovery.filterDiscoveryTree: dynamic Realm '%s' has no authorized"
                                + " children, omitting entire Realm",
                        realm.name);
            }
        }
        return filteredUniverse;
    }

    /**
     * Recursively copies {@code node}, retaining only those children (and their subtrees) for which
     * the caller is authorized. Authorization gates are applied at two node types:
     *
     * <ul>
     *   <li>{@code Namespace} — covers all nodes placed under the KubernetesApi realm by the
     *       built-in Kubernetes discovery or by Agents using the KUBERNETES fill algorithm. The
     *       entire subtree rooted at an unauthorized Namespace is dropped.
     *   <li>{@code CryostatAgent} — covers Agent-published target nodes that live directly under an
     *       agent-owned Realm (NONE fill strategy, no Namespace ancestor). Authorization is checked
     *       using the {@code discovery.cryostat.io/namespace} label on the node itself; if the
     *       label is absent the node is included.
     * </ul>
     *
     * <p>For both types the namespace string for the SSAR check is resolved first from the {@code
     * discovery.cryostat.io/namespace} label, falling back to the node name for Namespace nodes.
     * When {@code rawToken} is blank all nodes are included unchanged (PERMISSIVE / no-token path).
     */
    private DiscoveryNode filterNodeDfs(DiscoveryNode node, String rawToken) {
        DiscoveryNode copy = copyNode(node);
        for (DiscoveryNode child : node.children) {
            if (KubeDiscoveryNodeType.NAMESPACE.getKind().equals(child.nodeType)) {
                // Namespace node: gate the entire subtree on the namespace SSAR check.
                // Namespace label preferred over node name as the authoritative namespace value.
                String namespace =
                        StringUtils.isNotBlank(
                                        child.labels.get(
                                                KubeEndpointSlicesDiscovery
                                                        .DISCOVERY_NAMESPACE_LABEL_KEY))
                                ? child.labels.get(
                                        KubeEndpointSlicesDiscovery.DISCOVERY_NAMESPACE_LABEL_KEY)
                                : child.name;
                logger.debugf(
                        "Discovery.filterNodeDfs: Namespace node '%s' namespace='%s' rawToken=%s",
                        child.name,
                        namespace,
                        StringUtils.isBlank(rawToken)
                                ? "<blank>"
                                : "<present, length=" + rawToken.length() + ">");
                if (StringUtils.isBlank(rawToken)
                        || userAuthorizer.isAuthorized(
                                "discoverynodes", "read", namespace, rawToken)) {
                    logger.debugf("Discovery.filterNodeDfs: ALLOWED Namespace '%s'", namespace);
                    copy.children.add(filterNodeDfs(child, rawToken));
                } else {
                    logger.debugf(
                            "Discovery.filterNodeDfs: DENIED Namespace '%s', pruning subtree",
                            namespace);
                }
            } else if (BaseNodeType.AGENT.getKind().equals(child.nodeType)) {
                // CryostatAgent node with no Namespace ancestor (NONE fill strategy): the agent
                // itself carries the discovery.cryostat.io/namespace label. If the label is absent
                // there is no namespace context to filter on, so include the node.
                String namespace =
                        child.labels != null
                                ? child.labels.get(
                                        KubeEndpointSlicesDiscovery.DISCOVERY_NAMESPACE_LABEL_KEY)
                                : null;
                if (StringUtils.isBlank(namespace)) {
                    logger.debugf(
                            "Discovery.filterNodeDfs: CryostatAgent '%s' has no namespace label,"
                                    + " including",
                            child.name);
                    copy.children.add(filterNodeDfs(child, rawToken));
                } else {
                    logger.debugf(
                            "Discovery.filterNodeDfs: CryostatAgent '%s' namespace='%s'"
                                    + " rawToken=%s",
                            child.name,
                            namespace,
                            StringUtils.isBlank(rawToken)
                                    ? "<blank>"
                                    : "<present, length=" + rawToken.length() + ">");
                    if (StringUtils.isBlank(rawToken)
                            || userAuthorizer.isAuthorized(
                                    "discoverynodes", "read", namespace, rawToken)) {
                        logger.debugf(
                                "Discovery.filterNodeDfs: ALLOWED CryostatAgent '%s' in"
                                        + " namespace '%s'",
                                child.name, namespace);
                        copy.children.add(filterNodeDfs(child, rawToken));
                    } else {
                        logger.debugf(
                                "Discovery.filterNodeDfs: DENIED CryostatAgent '%s' in namespace"
                                        + " '%s', pruning",
                                child.name, namespace);
                    }
                }
            } else {
                copy.children.add(filterNodeDfs(child, rawToken));
            }
        }
        return copy;
    }

    @SuppressFBWarnings("DLS_DEAD_LOCAL_STORE")
    private DiscoveryNode mergeRealms(DiscoveryNode universe) {
        DiscoveryNode mergedRoot = new DiscoveryNode();
        mergedRoot.id = universe.id;
        mergedRoot.name = universe.name;
        mergedRoot.nodeType = universe.nodeType;
        mergedRoot.labels = new HashMap<>(universe.labels);
        mergedRoot.children = new ArrayList<>();

        DiscoveryNode syntheticRealm = new DiscoveryNode();
        syntheticRealm.id = SYNTHETIC_REALM_ID;
        syntheticRealm.name = SYNTHETIC_REALM_NAME;
        syntheticRealm.nodeType = BaseNodeType.REALM.getKind();
        syntheticRealm.labels = new HashMap<>();
        syntheticRealm.children = new ArrayList<>();
        mergedRoot.children.add(syntheticRealm);

        var mergedNodes = new HashMap<Pair<String, String>, DiscoveryNode>();

        var builtinRealmIds =
                DiscoveryPlugin.find("#DiscoveryPlugin.getBuiltinRealmIds")
                        .project(UUID.class)
                        .list();

        for (var realm : universe.children) {
            var stack = new ArrayDeque<NodeContext>();
            for (var child : realm.children) {
                stack.push(new NodeContext(child, null, builtinRealmIds.contains(realm.id)));
            }

            while (!stack.isEmpty()) {
                var ctx = stack.pop();
                var sourceNode = ctx.node;
                var mergedParent = ctx.parent;
                var fromBuiltin = ctx.fromBuiltin;

                Pair<String, String> key = Pair.of(sourceNode.nodeType, sourceNode.name);

                DiscoveryNode mergedNode;
                if (mergedParent == null) {
                    mergedNode = mergedNodes.computeIfAbsent(key, k -> copyNode(sourceNode));
                    syntheticRealm.children.add(mergedNode);
                    if (fromBuiltin) {
                        mergeNodeProperties(mergedNode, sourceNode);
                    }
                } else {
                    mergedNode =
                            mergedParent.children.stream()
                                    .filter(
                                            n ->
                                                    n.nodeType.equals(sourceNode.nodeType)
                                                            && n.name.equals(sourceNode.name))
                                    .findFirst()
                                    .orElseGet(
                                            () -> {
                                                var node = copyNode(sourceNode);
                                                mergedParent.children.add(node);
                                                return node;
                                            });

                    // if we have collisions, prefer the node which came from a builtin plugin
                    // and merge properties from discovery plugins in
                    if (fromBuiltin) {
                        mergeNodeProperties(mergedNode, sourceNode);
                    }
                }

                if (sourceNode.children != null && !sourceNode.children.isEmpty()) {
                    for (var child : sourceNode.children) {
                        stack.push(new NodeContext(child, mergedNode, fromBuiltin));
                    }
                }
            }
        }

        return mergedRoot;
    }

    private DiscoveryNode copyNode(DiscoveryNode source) {
        var copy = new DiscoveryNode();
        copy.id = source.id;
        copy.name = source.name;
        copy.nodeType = source.nodeType;
        copy.labels = new HashMap<>(source.labels);
        copy.children = new ArrayList<>();
        copy.target = source.target;
        return copy;
    }

    private void mergeNodeProperties(DiscoveryNode target, DiscoveryNode source) {
        if (source.id != null) {
            target.id = source.id;
        }
        if (source.labels != null) {
            target.labels.putAll(source.labels);
        }
        if (source.target != null) {
            target.target = source.target;
        }
    }

    private static record NodeContext(
            DiscoveryNode node, DiscoveryNode parent, boolean fromBuiltin) {}
}
