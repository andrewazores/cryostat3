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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class DiscoveryNodeSoftDeletionTest extends AbstractTransactionalTestBase {
    @Inject EntityManager entityManager;

    @Test
    @Transactional
    public void testSoftDeleteSetsTimestamp() {
        DiscoveryNode node = createTestNode();
        node.persist();

        assertNull(node.deletedAt);
        assertFalse(node.isDeleted());

        node.softDelete();

        assertNotNull(node.deletedAt);
        assertTrue(node.isDeleted());
        assertTrue(node.deletedAt.isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    @Transactional
    public void testUndeleteClearsTimestamp() {
        DiscoveryNode node = createTestNode();
        node.persist();
        node.softDelete();

        assertTrue(node.isDeleted());

        node.undelete();

        assertNull(node.deletedAt);
        assertFalse(node.isDeleted());
    }

    @Test
    @Transactional
    public void testFindActiveExcludesDeleted() {
        DiscoveryNode active = createTestNode("active");
        active.persist();

        DiscoveryNode deleted = createTestNode("deleted");
        deleted.persist();
        deleted.softDelete();
        entityManager.flush();
        entityManager.clear();

        List<DiscoveryNode> activeNodes = DiscoveryNode.findActive();

        assertTrue(activeNodes.stream().anyMatch(n -> n.id.equals(active.id)));
        assertFalse(activeNodes.stream().anyMatch(n -> n.id.equals(deleted.id)));
    }

    @Test
    @Transactional
    public void testByTypeWithNameExcludesDeleted() {
        DiscoveryNode node = createTestNode();
        node.persist();

        node.softDelete();
        node.persist();

        Optional<DiscoveryNode> found =
                DiscoveryNode.find(
                                "nodeType = ?1 AND name = ?2 AND deletedAt IS NULL",
                                node.nodeType,
                                node.name)
                        .firstResultOptional();

        assertFalse(found.isPresent());
    }

    @Test
    @Transactional
    public void testFindAllByNodeTypeIncludesDeleted() {
        DiscoveryNode active = createTestNode("active", "TestType");
        active.persist();

        DiscoveryNode deleted = createTestNode("deleted", "TestType");
        deleted.persist();
        deleted.softDelete();
        deleted.persist();

        NodeType testType =
                new NodeType() {
                    @Override
                    public String getKind() {
                        return "TestType";
                    }

                    @Override
                    public int ordinal() {
                        return 0;
                    }
                };

        List<DiscoveryNode> nodes = DiscoveryNode.findAllByNodeType(testType);

        assertTrue(nodes.contains(active));
        assertTrue(nodes.contains(deleted));
    }

    @Test
    @Transactional
    public void testFindDeletedOnlyReturnsDeleted() {
        DiscoveryNode active = createTestNode("active");
        active.persist();

        DiscoveryNode deleted = createTestNode("deleted");
        deleted.persist();
        deleted.softDelete();
        entityManager.flush();
        entityManager.clear();

        List<DiscoveryNode> deletedNodes = DiscoveryNode.findDeleted();

        assertFalse(deletedNodes.stream().anyMatch(n -> n.id.equals(active.id)));
        assertTrue(deletedNodes.stream().anyMatch(n -> n.id.equals(deleted.id)));
    }

    private DiscoveryNode createTestNode() {
        return createTestNode("test-node");
    }

    private DiscoveryNode createTestNode(String name) {
        return createTestNode(name, "TestType");
    }

    private DiscoveryNode createTestNode(String name, String nodeType) {
        DiscoveryNode node = new DiscoveryNode();
        node.name = name;
        node.nodeType = nodeType;
        node.labels = new HashMap<>();
        node.children = new ArrayList<>();
        return node;
    }
}
