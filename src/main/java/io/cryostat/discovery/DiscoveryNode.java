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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.cryostat.discovery.NodeType.BaseNodeType;
import io.cryostat.targets.Target;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Parameters;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jboss.logging.Logger;

/**
 * Represents a node in the overall discovery tree. Nodes either have a {@link
 * io.cryostat.targets.Target} reference or a list of children (which are also DiscoveryNodes),
 * never both. A node with a target, known as a TargetNode, represents a discovered JVM process. A
 * node with a list of children, known as an EnvironmentMode, represents some intermediate object
 * such as a {@link io.cryostat.discovery.DiscoveryPlugin}, a Podman Pod, a Kubernetes Deployment,
 * etc. All {@link io.cryostat.targets.Target} instances are associated with a DiscoveryNode which
 * places them in the tree.
 */
@Entity
@EntityListeners(DiscoveryNode.Listener.class)
@NamedQueries({
    @NamedQuery(
            name = "DiscoveryNode.byTypeWithName",
            query = "from DiscoveryNode where nodeType = :nodeType and name = :name")
})
@Table(indexes = {@Index(columnList = "nodeType"), @Index(columnList = "nodeType, name")})
public class DiscoveryNode extends PanacheEntity {

    public static final String NODE_TYPE = "nodeType";

    @Column(unique = false, nullable = false, updatable = false)
    @JsonView(Views.Flat.class)
    @NotBlank
    public String name;

    @Column(unique = false, nullable = false, updatable = false)
    @JsonView(Views.Flat.class)
    @NotBlank
    public String nodeType;

    @JdbcTypeCode(SqlTypes.JSON)
    @NotNull
    @JsonView(Views.Flat.class)
    public Map<String, String> labels = new HashMap<>();

    @OneToMany(fetch = FetchType.LAZY, orphanRemoval = true, mappedBy = "parent")
    @JsonIgnore
    @Nullable
    public List<DiscoveryNode> children = new ArrayList<>();

    @Nullable
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parentNode")
    @JsonIgnore
    public DiscoveryNode parent;

    @OneToOne(
            mappedBy = "discoveryNode",
            cascade = {CascadeType.ALL},
            fetch = FetchType.LAZY,
            orphanRemoval = true)
    @Nullable
    @JsonInclude(value = Include.NON_NULL)
    @JsonView(Views.Flat.class)
    public Target target;

    /**
     * Timestamp when this discovery node was soft-deleted. NULL indicates the node is active (not
     * deleted).
     */
    @Column(name = "deleted_at")
    @Nullable
    @JsonIgnore
    public Instant deletedAt;

    @Override
    public int hashCode() {
        return Objects.hash(id, name, nodeType, labels, children, target);
    }

    /**
     * Check if this discovery node is soft-deleted.
     *
     * @return true if the node has been soft-deleted, false otherwise
     */
    @JsonIgnore
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * Soft-delete this discovery node by setting the deletedAt timestamp to now. Does not persist
     * the change automatically.
     */
    public void softDelete() {
        this.deletedAt = Instant.now();
        this.softDeletePending = true;
    }

    /**
     * Undelete this discovery node by clearing the deletedAt timestamp. Does not persist the change
     * automatically.
     */
    public void undelete() {
        this.deletedAt = null;
        this.undeletePending = true;
    }

    @JsonIgnore private transient boolean softDeletePending = false;

    @JsonIgnore private transient boolean undeletePending = false;

    /**
     * Get the active (non-deleted) children of this node. This is the default view for API
     * responses.
     *
     * @return list of active children
     */
    @JsonProperty("children")
    @JsonInclude
    @JsonView(Views.Nested.class)
    public List<DiscoveryNode> getChildren() {
        return children.stream().filter(child -> !child.isDeleted()).toList();
    }

    /**
     * Get all children including soft-deleted ones.
     *
     * @return list of all children
     */
    @JsonIgnore
    public List<DiscoveryNode> getAllChildren() {
        return children;
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    public static DiscoveryNode getUniverse() {
        return DiscoveryNode.find(NODE_TYPE, NodeType.BaseNodeType.UNIVERSE.getKind())
                .<DiscoveryNode>singleResult();
    }

    public static Optional<DiscoveryNode> getRealm(String name) {
        return DiscoveryNode.<DiscoveryNode>find(
                        "#DiscoveryNode.byTypeWithName",
                        Parameters.with("nodeType", BaseNodeType.REALM.getKind()).and("name", name))
                .firstResultOptional();
    }

    public static Optional<DiscoveryNode> getChild(
            DiscoveryNode node, Predicate<DiscoveryNode> predicate) {
        return node.children.stream().filter(predicate).findFirst();
    }

    static DiscoveryNode byTypeWithName(
            NodeType nodeType,
            String name,
            Predicate<DiscoveryNode> predicate,
            Consumer<DiscoveryNode> customizer) {
        var kind = nodeType.getKind();
        // First check for active nodes
        Optional<DiscoveryNode> activeNode =
                DiscoveryNode.<DiscoveryNode>find(
                                "#DiscoveryNode.byTypeWithName",
                                Parameters.with("nodeType", kind).and("name", name))
                        .stream()
                        .filter(n -> !n.isDeleted())
                        .filter(predicate)
                        .findFirst();

        if (activeNode.isPresent()) {
            return activeNode.get();
        }

        // Check for soft-deleted nodes that can be undeleted
        Optional<DiscoveryNode> deletedNode =
                DiscoveryNode.<DiscoveryNode>find(
                                "#DiscoveryNode.byTypeWithName",
                                Parameters.with("nodeType", kind).and("name", name))
                        .stream()
                        .filter(DiscoveryNode::isDeleted)
                        .filter(predicate)
                        .findFirst();

        if (deletedNode.isPresent()) {
            return QuarkusTransaction.joiningExisting()
                    .call(
                            () -> {
                                DiscoveryNode node = deletedNode.get();
                                node.undelete();
                                customizer.accept(node);
                                node.persist();
                                return node;
                            });
        }

        // Create new node if none found
        return QuarkusTransaction.joiningExisting()
                .call(
                        () -> {
                            DiscoveryNode node = new DiscoveryNode();
                            node.name = name;
                            node.nodeType = kind;
                            node.labels = new HashMap<>();
                            node.children = new ArrayList<>();
                            node.target = null;
                            customizer.accept(node);
                            node.persist();
                            return node;
                        });
    }

    public static List<DiscoveryNode> findAllByNodeType(NodeType nodeType) {
        return DiscoveryNode.find(DiscoveryNode.NODE_TYPE, nodeType.getKind()).list();
    }

    /**
     * Find all active (non-deleted) discovery nodes.
     *
     * @return list of active nodes
     */
    public static List<DiscoveryNode> findActive() {
        return find("deletedAt IS NULL").list();
    }

    /**
     * Find all soft-deleted discovery nodes.
     *
     * @return list of deleted nodes
     */
    public static List<DiscoveryNode> findDeleted() {
        return find("deletedAt IS NOT NULL").list();
    }

    public static DiscoveryNode environment(String name, NodeType nodeType) {
        return byTypeWithName(nodeType, name, n -> true, n -> {});
    }

    public static DiscoveryNode target(Target target, NodeType nodeType) {
        return target(target, nodeType, n -> {});
    }

    public static DiscoveryNode target(
            Target target, NodeType nodeType, Consumer<DiscoveryNode> customizer) {
        return byTypeWithName(
                nodeType,
                target.connectUrl.toString(),
                n -> true,
                n -> {
                    n.target = target;
                    n.labels.putAll(target.labels);
                    customizer.accept(n);
                });
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        DiscoveryNode other = (DiscoveryNode) obj;
        return Objects.equals(target, other.target)
                && Objects.equals(labels, other.labels)
                && Objects.equals(children, other.children);
    }

    @Override
    public String toString() {
        return "DiscoveryNode{"
                + "name='"
                + name
                + '\''
                + ", nodeType='"
                + nodeType
                + '\''
                + ", children="
                + children
                + '}';
    }

    @ApplicationScoped
    static class Listener {

        @Inject Logger logger;
        @Inject EventBus bus;

        @PrePersist
        void prePersist(DiscoveryNode node) {
            if (node.children == null) {
                node.children = new ArrayList<>();
            }
            if (node.labels == null) {
                node.labels = new HashMap<>();
            }
        }

        @PostPersist
        void postPersist(DiscoveryNode node) {
            logger.debugv(
                    "DiscoveryNode created: id={0}, name={1}, type={2}",
                    node.id, node.name, node.nodeType);
        }

        @PostUpdate
        void postUpdate(DiscoveryNode node) {
            // Check for soft-delete or undelete operations
            if (node.softDeletePending) {
                node.softDeletePending = false;
                logger.infov(
                        "DiscoveryNode soft-deleted: id={0}, name={1}, type={2}",
                        node.id, node.name, node.nodeType);
            } else if (node.undeletePending) {
                node.undeletePending = false;
                logger.infov(
                        "DiscoveryNode undeleted: id={0}, name={1}, type={2}",
                        node.id, node.name, node.nodeType);
            } else {
                logger.debugv(
                        "DiscoveryNode updated: id={0}, name={1}, type={2}",
                        node.id, node.name, node.nodeType);
            }
        }

        @PostRemove
        void postRemove(DiscoveryNode node) {
            logger.infov(
                    "DiscoveryNode hard-deleted: id={0}, name={1}, type={2}",
                    node.id, node.name, node.nodeType);
        }
    }

    public static class Views {
        public static class Flat {}

        public static class Nested extends Flat {}
    }
}
