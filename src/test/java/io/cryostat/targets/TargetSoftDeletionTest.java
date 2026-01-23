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
package io.cryostat.targets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.discovery.DiscoveryNode;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class TargetSoftDeletionTest extends AbstractTransactionalTestBase {

    @Inject TargetConnectionManager connectionManager;

    @Test
    @Transactional
    public void testSoftDeleteSetsTimestamp() {
        Target target = createTestTarget();
        target.persist();

        assertNull(target.deletedAt);
        assertFalse(target.isDeleted());

        target.softDelete();

        assertNotNull(target.deletedAt);
        assertTrue(target.isDeleted());
        assertTrue(target.deletedAt.isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    @Transactional
    public void testUndeleteClearsTimestamp() {
        Target target = createTestTarget();
        target.persist();
        target.softDelete();

        assertTrue(target.isDeleted());

        target.undelete();

        assertNull(target.deletedAt);
        assertFalse(target.isDeleted());
    }

    @Test
    @Transactional
    public void testFindActiveExcludesDeleted() {
        Target active = createTestTarget("active");
        active.persist();

        Target deleted = createTestTarget("deleted");
        deleted.persist();
        deleted.softDelete();
        deleted.persist();

        List<Target> activeTargets = Target.findActive();

        assertTrue(activeTargets.contains(active));
        assertFalse(activeTargets.contains(deleted));
    }

    @Test
    @Transactional
    public void testGetTargetByIdIncludesDeletedRecords() {
        Target target = createTestTarget();
        target.persist();
        long id = target.id;

        target.softDelete();
        target.persist();

        Target found = Target.getTargetById(id);
        assertNotNull(found);
        assertTrue(found.isDeleted());
    }

    @Test
    @Transactional
    public void testGetTargetByIdIncludesDeleted() {
        Target target = createTestTarget();
        target.persist();
        long id = target.id;

        target.softDelete();
        target.persist();

        Target found = Target.getTargetById(id);
        assertNotNull(found);
        assertTrue(found.isDeleted());
    }

    @Test
    @Transactional
    public void testCreateOrUndeleteCreatesNew() {
        URI connectUrl = URI.create("service:jmx:rmi:///jndi/rmi://localhost:9091/jmxrmi");

        Target target = Target.createOrUndelete(connectUrl);

        assertNotNull(target);
        assertEquals(connectUrl, target.connectUrl);
        assertFalse(target.isDeleted());
    }

    @Test
    @Transactional
    public void testCreateOrUndeleteUndeletesExisting() {
        Target target = createTestTarget();
        target.persist();
        URI connectUrl = target.connectUrl;

        target.softDelete();
        target.persist();

        Target undeleted = Target.createOrUndelete(connectUrl);

        assertEquals(target.id, undeleted.id);
        assertFalse(undeleted.isDeleted());
    }

    @Test
    @Transactional
    public void testGetTargetByJvmIdIncludesDeleted() {
        Target target = createTestTarget();
        target.jvmId = "test-jvm-id";
        target.persist();

        target.softDelete();
        target.persist();

        Optional<Target> found = Target.getTargetByJvmId("test-jvm-id");
        assertTrue(found.isPresent());
        assertTrue(found.get().isDeleted());
    }

    @Test
    @Transactional
    public void testGetTargetByConnectUrlIncludesDeleted() {
        Target target = createTestTarget();
        target.persist();
        URI connectUrl = target.connectUrl;

        target.softDelete();
        target.persist();

        Target found = Target.getTargetByConnectUrl(connectUrl);
        assertNotNull(found);
        assertTrue(found.isDeleted());
    }

    @Test
    @Transactional
    public void testFindDeletedOnlyReturnsDeleted() {
        Target active = createTestTarget("active");
        active.persist();

        Target deleted = createTestTarget("deleted");
        deleted.persist();
        deleted.softDelete();
        deleted.persist();

        List<Target> deletedTargets = Target.findDeleted();

        assertFalse(deletedTargets.contains(active));
        assertTrue(deletedTargets.contains(deleted));
    }

    @Test
    @Transactional
    public void testFindAllIncludingDeletedReturnsAll() {
        Target active = createTestTarget("active");
        active.persist();

        Target deleted = createTestTarget("deleted");
        deleted.persist();
        deleted.softDelete();
        deleted.persist();

        List<Target> allTargets = Target.findAllIncludingDeleted();

        assertTrue(allTargets.contains(active));
        assertTrue(allTargets.contains(deleted));
    }

    private Target createTestTarget() {
        return createTestTarget("test-target");
    }

    private Target createTestTarget(String alias) {
        Target target = new Target();
        target.connectUrl =
                URI.create(
                        "service:jmx:rmi:///jndi/rmi://localhost:"
                                + (9090 + Math.abs(alias.hashCode() % 1000))
                                + "/jmxrmi");
        target.alias = alias;
        target.labels = new HashMap<>();
        target.annotations = new Target.Annotations();
        target.activeRecordings = new ArrayList<>();

        // Create a minimal DiscoveryNode for the target
        DiscoveryNode node = new DiscoveryNode();
        node.name = target.connectUrl.toString();
        node.nodeType = "JVM";
        node.labels = new HashMap<>();
        node.children = new ArrayList<>();
        node.target = target;
        target.discoveryNode = node;

        return target;
    }
}
