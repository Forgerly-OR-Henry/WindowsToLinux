package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Real CLASS/H2 adoption and external lifecycle calls through an authenticated controlled SSH peer. / 经已认证受控SSH对等端执行真实CLASS/H2接管与外部生命周期调用。 */
class DeploymentExternalApplicationsTest extends DeploymentApplicationsFixture {
    enum DriftType {
        SCAN_FINGERPRINT, ADOPT_MANAGED, ADOPTED_ENDPOINT, ACTION_MANAGED
    }

    enum ReviewDriftType {
        PRESENTATION, ENDPOINT, OBSERVATION
    }

    @Test
    void scanAdoptAndRepeatOriginalTaskPersistOneExactRegistrationAndReference() throws Exception {
        enable();
        try (var ssh = ssh(); var runtime = open()) {
            start(runtime, true);
            server(runtime, ssh.server().getPort(), "tester");
            var actual = context(runtime);
            peer.scanIssues = List.of("DOCKER_PERMISSION");
            var publicScan = await(new DeploymentApplicationsService(actual).scan(new ServerRef("server")));
            assertEquals(Outcome.SUCCEEDED, publicScan.outcome());
            assertEquals(List.of("DOCKER_PERMISSION"), publicScan.value().orElseThrow().issues());
            assertEquals(1, publicScan.value().orElseThrow().applications().size());
            assertEquals(0, peer.effects.get(),
                    "A nonfatal Docker permission issue must preserve read-only scan behavior");
            var external = new DeploymentExternalApplications(actual);
            var handle = rootTask(runtime, task -> {
                var scanned = external.scan(new ServerRef("server"), task);
                assertEquals(Outcome.SUCCEEDED, scanned.outcome());
                var target = scanned.value().orElseThrow().applications().getFirst();
                var adopted = external.adopt(new ServerRef("server"), target, task);
                assertEquals(Outcome.SUCCEEDED, adopted.outcome());
                String id = adopted.value().orElseThrow();
                int reads = peer.reads.get();
                var repeated = external.adopt(new ServerRef("server"), target, task);
                assertEquals(Outcome.SUCCEEDED, repeated.outcome());
                assertEquals(Optional.of(id), repeated.value());
                assertEquals(reads, peer.reads.get(),
                        "Original-task adoption repeat must only read its durable row/reference");
                var rows = external.list(task);
                assertEquals(1, rows.size());
                assertEquals(id, rows.getFirst().id());
                assertEquals(target.identity(), rows.getFirst().target().identity());
                assertEquals(target.fingerprint(), rows.getFirst().target().fingerprint());
                assertEquals("External service", rows.getFirst().displayName());
                var operation = new DeploymentManagementJournal(actual).find(task).orElseThrow();
                assertEquals(task.id(), operation.taskId());
                assertEquals("FINISHED", operation.phase());
                assertEquals(id, operation.facts().path("registrationId").textValue());
                var reference = actual.servers()
                        .findReference(new ServerRef("server"), operation.facts().path("businessKey").textValue(), task)
                        .orElseThrow();
                assertEquals(operation.facts().path("referenceId").textValue(), reference.id().toString());
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM external_application"));
                return success();
            });
            assertEquals(Outcome.SUCCEEDED, await(handle).outcome());
            var listed = await(new DeploymentApplicationsService(actual).externalApplications());
            assertEquals(Outcome.SUCCEEDED, listed.outcome());
            assertEquals(1, listed.value().orElseThrow().size());
            var selected = listed.value().orElseThrow().getFirst();
            assertEquals(Outcome.SUCCEEDED,
                    await(new DeploymentApplicationsService(actual).externalAction(selected, "REFRESH_STATUS"))
                            .outcome());
            assertEquals(0, peer.effects.get());
            assertEquals(1, scalar(runtime,
                    "SELECT COUNT(*) FROM mod_server_reference WHERE business_key LIKE 'deployment.external.%' AND state='ACTIVE'"));
            assertNoActiveResources(runtime);
        }
    }

    @ParameterizedTest(name = "external identity gate: {0}")
    @EnumSource(DriftType.class)
    void reviewedExternalIdentityChangesNeverDispatchEffects(DriftType drift) throws Exception {
        enable();
        try (var ssh = ssh(); var runtime = open()) {
            start(runtime, true);
            server(runtime, ssh.server().getPort(), "tester");
            var service = new DeploymentApplicationsService(context(runtime));
            var scanned = await(service.scan(new ServerRef("server"))).value().orElseThrow().applications().getFirst();
            if (drift == DriftType.SCAN_FINGERPRINT || drift == DriftType.ADOPT_MANAGED) {
                if (drift == DriftType.SCAN_FINGERPRINT)
                    peer.fingerprint = "b".repeat(64);
                else
                    peer.managed = true;
                assertEquals(Outcome.FAILED, await(service.adopt(new ServerRef("server"), scanned)).outcome());
                try (var access = scope(runtime, true);
                        var connection = access.openConnection();
                        var statement = connection.createStatement();
                        var rows = statement.executeQuery("SELECT COUNT(*) FROM external_application")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getLong(1));
                }
                assertEquals(0, scalar(runtime,
                        "SELECT COUNT(*) FROM mod_server_reference WHERE business_key LIKE 'deployment.external.%'"));
            } else {
                assertEquals(Outcome.SUCCEEDED, await(service.adopt(new ServerRef("server"), scanned)).outcome());
                var selected = await(service.externalApplications()).value().orElseThrow().getFirst();
                int reads = peer.reads.get();
                if (drift == DriftType.ADOPTED_ENDPOINT)
                    server(runtime, ssh.server().getPort(), "changed");
                else
                    peer.managed = true;
                assertEquals(Outcome.FAILED, await(service.externalAction(selected, "START")).outcome());
                if (drift == DriftType.ADOPTED_ENDPOINT)
                    assertEquals(reads, peer.reads.get(), "Stored endpoint drift must fail before a connection");
            }
            assertEquals(0, peer.effects.get(),
                    "Managed or changed identities cannot receive an external lifecycle effect");
            assertNoActiveResources(runtime);
        }
    }

    @Test
    void durableInsertWithLostJournalAcknowledgementRecoversOriginalRowAndReferenceWithoutScanOrInsert()
            throws Exception {
        enable();
        try (var ssh = ssh(); var runtime = open()) {
            start(runtime, true);
            server(runtime, ssh.server().getPort(), "tester");
            var service = new DeploymentApplicationsService(context(runtime));
            var scanned = await(service.scan(new ServerRef("server"))).value().orElseThrow().applications().getFirst();
            schemaFault(runtime,
                    "ALTER TABLE deployment_management_operation ADD CONSTRAINT lost_adoption_ack CHECK(phase<>'REGISTERED')");
            var handle = service.adopt(new ServerRef("server"), scanned);
            awaitState(handle);
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            assertTrue(handle.snapshot().outcome().isEmpty());
            String originalId;
            UUID referenceId;
            try (var access = scope(runtime, true);
                    var connection = access.openConnection();
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                            "SELECT external_application_id,identity_fingerprint FROM external_application")) {
                assertTrue(rows.next());
                originalId = rows.getString(1);
                assertEquals(scanned.fingerprint(), rows.getString(2));
                assertFalse(rows.next());
            }
            try (var connection = runtime.persistence().database().openConnection();
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                            "SELECT reference_id,state FROM mod_server_reference WHERE business_key='deployment.external."
                                    + originalId + "'")) {
                assertTrue(rows.next());
                referenceId = rows.getObject(1, UUID.class);
                assertEquals("RESERVED", rows.getString(2));
                assertFalse(rows.next());
            }
            int reads = peer.reads.get();
            schemaFault(runtime, "ALTER TABLE deployment_management_operation DROP CONSTRAINT lost_adoption_ack");
            var recovered = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, recovered.state(), recovered.toString());
            assertEquals(Optional.of(Outcome.SUCCEEDED), recovered.outcome());
            assertEquals(reads, peer.reads.get());
            assertEquals(0, peer.effects.get());
            var listed = await(service.externalApplications()).value().orElseThrow();
            assertEquals(1, listed.size());
            assertEquals(originalId, listed.getFirst().id());
            try (var connection = runtime.persistence().database().openConnection();
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                            "SELECT reference_id,state FROM mod_server_reference WHERE business_key='deployment.external."
                                    + originalId + "'")) {
                assertTrue(rows.next());
                assertEquals(referenceId, rows.getObject(1, UUID.class));
                assertEquals("ACTIVE", rows.getString(2));
                assertFalse(rows.next());
            }
            assertNoActiveResources(runtime);
        }
    }

    @Test
    void unknownStartUsesOriginalReadOnlyObservationAndNeverRepeatsTheEffect() throws Exception {
        enable();
        try (var ssh = ssh(); var runtime = open()) {
            start(runtime, true);
            server(runtime, ssh.server().getPort(), "tester");
            var service = new DeploymentApplicationsService(context(runtime));
            var target = await(service.scan(new ServerRef("server"))).value().orElseThrow().applications().getFirst();
            assertEquals(Outcome.SUCCEEDED, await(service.adopt(new ServerRef("server"), target)).outcome());
            var selected = await(service.externalApplications()).value().orElseThrow().getFirst();
            peer.loseStartAck.set(true);
            var handle = service.externalAction(selected, "START");
            awaitState(handle);
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            assertEquals(1, peer.effects.get());
            assertTrue(handle.snapshot().outcome().isEmpty());
            assertTrue(service.tasks().stream().anyMatch(value -> value.id().equals(handle.id())));
            int reads = peer.reads.get();
            var result = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, result.state(), result.toString());
            assertEquals(Optional.of(Outcome.SUCCEEDED), result.outcome());
            assertEquals(1, peer.effects.get());
            assertEquals(reads + 1, peer.reads.get(),
                    "Original recovery must issue exactly one read-only status observation");
            assertEquals("RUNNING",
                    await(service.externalApplications()).value().orElseThrow().getFirst().target().state());
            assertNoActiveResources(runtime);
        }
    }

    @Test
    void crossTaskRepeatPreservesOriginalIdAdoptionTimePresentationAndReferenceWithoutReservation() throws Exception {
        enable();
        try (var ssh = ssh(); var runtime = open()) {
            start(runtime, true);
            server(runtime, ssh.server().getPort(), "tester");
            var service = new DeploymentApplicationsService(context(runtime));
            var target = await(service.scan(new ServerRef("server"))).value().orElseThrow().applications().getFirst();
            var first = service.adopt(new ServerRef("server"), target);
            String id = await(first).value().orElseThrow();
            var selected = await(service.externalApplications()).value().orElseThrow().getFirst();
            assertEquals(Outcome.SUCCEEDED, await(service.editExternal(selected, "Original label", "WEBSITE",
                    Optional.of("https://demo.debug.gold/"))).outcome());
            var before = registration(runtime, id);
            int journals = managementCount(runtime);
            int reads = peer.reads.get();
            var repeated = service.adopt(new ServerRef("server"), target);
            assertNotEquals(first.id(), repeated.id());
            assertEquals(Optional.of(id), await(repeated).value());
            assertEquals(reads + 1, peer.reads.get(), "Cross-task reuse must verify the exact remote identity once");
            assertEquals(before, registration(runtime, id),
                    "Repeat must preserve adoptedAt and every presentation/runtime field");
            assertEquals(journals, managementCount(runtime),
                    "Exact reuse cannot journal a new registration or reserve another reference");
            assertEquals(1,
                    scalar(runtime, "SELECT COUNT(*) FROM mod_server_reference WHERE business_key='deployment.external."
                            + id + "' AND state='ACTIVE'"));
            selected = await(service.externalApplications()).value().orElseThrow().getFirst();
            assertEquals("Original label", selected.displayName());
            assertEquals("WEBSITE", selected.category());
            assertEquals(Optional.of("https://demo.debug.gold/"), selected.accessUrl());
            assertEquals(0, peer.effects.get());
            assertNoActiveResources(runtime);
        }
    }

    @Test
    void fingerprintDriftRequiresExplicitReviewedReattachmentAndClearsOnlyOriginalPresentation() throws Exception {
        enable();
        try (var ssh = ssh(); var runtime = open()) {
            start(runtime, true);
            server(runtime, ssh.server().getPort(), "tester");
            var service = new DeploymentApplicationsService(context(runtime));
            var target = await(service.scan(new ServerRef("server"))).value().orElseThrow().applications().getFirst();
            String oldId = await(service.adopt(new ServerRef("server"), target)).value().orElseThrow();
            var selected = await(service.externalApplications()).value().orElseThrow().getFirst();
            assertEquals(Outcome.SUCCEEDED, await(service.editExternal(selected, "Old presentation", "WEBSITE",
                    Optional.of("https://demo.debug.gold/"))).outcome());
            selected = await(service.externalApplications()).value().orElseThrow().getFirst();
            var old = registration(runtime, oldId);
            int journals = managementCount(runtime);
            peer.fingerprint = "b".repeat(64);
            peer.expectedArgumentFingerprint = peer.fingerprint;
            var fresh = await(service.scan(new ServerRef("server"))).value().orElseThrow().applications().getFirst();
            int reads = peer.reads.get();
            assertEquals(Outcome.FAILED, await(service.adopt(new ServerRef("server"), fresh)).outcome());
            assertEquals(reads, peer.reads.get(), "Default drift rejection must occur before SSH or reservation");
            assertEquals(old, registration(runtime, oldId));
            assertEquals(journals, managementCount(runtime));
            var reviewed = await(service.reviewReattachment(selected, fresh));
            assertEquals(Outcome.SUCCEEDED, reviewed.outcome());
            var review = reviewed.value().orElseThrow();
            assertEquals(selected, review.original());
            assertEquals(fresh, review.candidate());
            assertEquals("tester@127.0.0.1:" + ssh.server().getPort(), review.oldEndpoint());
            var result = await(service.reattach(review));
            assertEquals(Outcome.SUCCEEDED, result.outcome());
            String newId = result.value().orElseThrow();
            assertNotEquals(oldId, newId);
            assertTrue(registration(runtime, oldId).isEmpty());
            var actual = await(service.externalApplications()).value().orElseThrow();
            assertEquals(1, actual.size());
            assertEquals(newId, actual.getFirst().id());
            assertEquals(fresh.fingerprint(), actual.getFirst().target().fingerprint());
            assertEquals("External service", actual.getFirst().displayName());
            assertEquals("APP", actual.getFirst().category());
            assertTrue(actual.getFirst().accessUrl().isEmpty());
            assertTrue(Long.parseLong(registration(runtime, newId).get("adopted_at")) >= Long
                    .parseLong(old.get("adopted_at")));
            assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM mod_server_reference WHERE business_key='deployment.external."
                            + oldId + "'"));
            assertEquals(1,
                    scalar(runtime, "SELECT COUNT(*) FROM mod_server_reference WHERE business_key='deployment.external."
                            + newId + "' AND state='ACTIVE'"));
            assertEquals(0, peer.effects.get());
            assertNoActiveResources(runtime);
        }
    }

    @Test
    void referencedServerEndpointCannotBeReboundThroughNormalProfileSave() throws Exception {
        enable();
        try (var ssh = ssh(); var runtime = open()) {
            start(runtime, true);
            server(runtime, ssh.server().getPort(), "tester");
            var service = new DeploymentApplicationsService(context(runtime));
            var target = await(service.scan(new ServerRef("server"))).value().orElseThrow().applications().getFirst();
            String oldId = await(service.adopt(new ServerRef("server"), target)).value().orElseThrow();
            var selected = await(service.externalApplications()).value().orElseThrow().getFirst();
            var before = registration(runtime, oldId);
            int reads = peer.reads.get(), journals = managementCount(runtime);
            assertThrows(SQLException.class,
                    () -> runtime.servers().saveServerProfile(
                            gold.debug.windowstolinux.app.service.contract.profile.ServerProfile.fromStored(
                                    new gold.debug.windowstolinux.app.db.entity.StoredServerProfile("server",
                                            "localhost", ssh.server().getPort(), "tester", "fixture-secret")),
                            "fixture-secret".toCharArray()));
            assertEquals(reads, peer.reads.get(), "Referenced endpoint rejection does not send SSH");
            assertEquals(journals, managementCount(runtime));
            assertEquals(before, registration(runtime, oldId));
            assertEquals(selected, await(service.externalApplications()).value().orElseThrow().getFirst());
            assertEquals("127.0.0.1", runtime.persistence().servers().findServerProfile("server").orElseThrow().host());
            assertEquals(1,
                    scalar(runtime, "SELECT COUNT(*) FROM mod_server_reference WHERE business_key='deployment.external."
                            + oldId + "' AND state='ACTIVE'"));
            assertEquals(0, peer.effects.get());
            assertNoActiveResources(runtime);
        }
    }

    @ParameterizedTest(name = "reattachment review drift: {0}")
    @EnumSource(ReviewDriftType.class)
    void staleReattachmentReviewCannotReplaceOriginalRowOrReference(ReviewDriftType drift) throws Exception {
        enable();
        try (var ssh = ssh(); var runtime = open()) {
            start(runtime, true);
            server(runtime, ssh.server().getPort(), "tester");
            var service = new DeploymentApplicationsService(context(runtime));
            var target = await(service.scan(new ServerRef("server"))).value().orElseThrow().applications().getFirst();
            String id = await(service.adopt(new ServerRef("server"), target)).value().orElseThrow();
            var selected = await(service.externalApplications()).value().orElseThrow().getFirst();
            peer.fingerprint = "b".repeat(64);
            peer.expectedArgumentFingerprint = peer.fingerprint;
            var fresh = await(service.scan(new ServerRef("server"))).value().orElseThrow().applications().getFirst();
            var review = await(service.reviewReattachment(selected, fresh)).value().orElseThrow();
            if (drift == ReviewDriftType.PRESENTATION)
                assertEquals(Outcome.SUCCEEDED,
                        await(service.editExternal(selected, "Later label", "APP", Optional.empty())).outcome());
            else if (drift == ReviewDriftType.ENDPOINT)
                server(runtime, ssh.server().getPort(), "changed");
            else
                peer.managed = true;
            var before = registration(runtime, id);
            int journals = managementCount(runtime), reads = peer.reads.get();
            assertEquals(Outcome.FAILED, await(service.reattach(review)).outcome());
            assertEquals(before, registration(runtime, id));
            assertEquals(journals, managementCount(runtime));
            if (drift != ReviewDriftType.OBSERVATION)
                assertEquals(reads, peer.reads.get());
            assertEquals(1,
                    scalar(runtime, "SELECT COUNT(*) FROM mod_server_reference WHERE business_key='deployment.external."
                            + id + "' AND state='ACTIVE'"));
            assertEquals(0, peer.effects.get());
            assertNoActiveResources(runtime);
        }
    }

    @Test
    void lostReplacementAcknowledgementRecoversExactNewRowAndBothOriginalReferencesWithoutRepeatingDelete()
            throws Exception {
        enable();
        try (var ssh = ssh(); var runtime = open()) {
            start(runtime, true);
            server(runtime, ssh.server().getPort(), "tester");
            var service = new DeploymentApplicationsService(context(runtime));
            var target = await(service.scan(new ServerRef("server"))).value().orElseThrow().applications().getFirst();
            String oldId = await(service.adopt(new ServerRef("server"), target)).value().orElseThrow();
            var selected = await(service.externalApplications()).value().orElseThrow().getFirst();
            peer.fingerprint = "b".repeat(64);
            peer.expectedArgumentFingerprint = peer.fingerprint;
            var fresh = await(service.scan(new ServerRef("server"))).value().orElseThrow().applications().getFirst();
            var review = await(service.reviewReattachment(selected, fresh)).value().orElseThrow();
            schemaFault(runtime,
                    "ALTER TABLE deployment_management_operation ADD CONSTRAINT lost_reattachment_ack CHECK(phase<>'REGISTERED')");
            var handle = service.reattach(review);
            awaitState(handle);
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            assertTrue(handle.snapshot().outcome().isEmpty());
            assertTrue(registration(runtime, oldId).isEmpty(),
                    "Atomic replacement committed before journal acknowledgement loss");
            String newId = await(service.externalApplications()).value().orElseThrow().getFirst().id();
            var row = registration(runtime, newId);
            assertNotEquals(oldId, newId);
            UUID reference;
            try (var connection = runtime.persistence().database().openConnection();
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                            "SELECT reference_id,state FROM mod_server_reference WHERE business_key='deployment.external."
                                    + newId + "'")) {
                assertTrue(rows.next());
                reference = rows.getObject(1, UUID.class);
                assertEquals("RESERVED", rows.getString(2));
                assertFalse(rows.next());
            }
            assertEquals(1,
                    scalar(runtime, "SELECT COUNT(*) FROM mod_server_reference WHERE business_key='deployment.external."
                            + oldId + "' AND state='ACTIVE'"));
            int reads = peer.reads.get();
            schemaFault(runtime, "ALTER TABLE deployment_management_operation DROP CONSTRAINT lost_reattachment_ack");
            var recovered = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, recovered.state(), recovered.toString());
            assertEquals(Optional.of(Outcome.SUCCEEDED), recovered.outcome());
            assertEquals(reads, peer.reads.get());
            assertEquals(row, registration(runtime, newId));
            assertTrue(registration(runtime, oldId).isEmpty());
            assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM mod_server_reference WHERE business_key='deployment.external."
                            + oldId + "'"));
            assertEquals(1, scalar(runtime, "SELECT COUNT(*) FROM mod_server_reference WHERE reference_id='" + reference
                    + "' AND state='ACTIVE'"));
            assertEquals(0, peer.effects.get());
            assertNoActiveResources(runtime);
        }
    }

    @Test
    void occupiedUnknownOriginalActionBlocksReplacementUntilItsOwnReadOnlyRecovery() throws Exception {
        enable();
        try (var ssh = ssh(); var runtime = open()) {
            start(runtime, true);
            server(runtime, ssh.server().getPort(), "tester");
            var service = new DeploymentApplicationsService(context(runtime));
            var target = await(service.scan(new ServerRef("server"))).value().orElseThrow().applications().getFirst();
            String id = await(service.adopt(new ServerRef("server"), target)).value().orElseThrow();
            var selected = await(service.externalApplications()).value().orElseThrow().getFirst();
            var review = await(service.reviewReattachment(selected, target)).value().orElseThrow();
            peer.loseStartAck.set(true);
            var original = service.externalAction(selected, "START");
            awaitState(original);
            assertEquals(TaskState.UNKNOWN, original.snapshot().state());
            var row = registration(runtime, id);
            var queued = service.reattach(review);
            assertEquals(TaskState.QUEUED, queued.snapshot().state(), queued.snapshot().toString());
            assertEquals(row, registration(runtime, id));
            assertEquals(1, peer.effects.get());
            queued.requestCancel();
            assertEquals(Outcome.CANCELLED, await(queued).outcome());
            assertEquals(TaskState.UNKNOWN, original.snapshot().state());
            assertTrue(original.snapshot().outcome().isEmpty());
            assertEquals(1,
                    scalar(runtime, "SELECT COUNT(*) FROM mod_server_reference WHERE business_key='deployment.external."
                            + id + "' AND state='ACTIVE'"));
            var settled = runtime.tasks().reconcile(original.id()).toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, settled.state());
            assertEquals(Optional.of(Outcome.SUCCEEDED), settled.outcome());
            assertEquals(Outcome.FAILED, await(service.reattach(review)).outcome(),
                    "Original observation changed the exact old row review");
            assertEquals(id, await(service.externalApplications()).value().orElseThrow().getFirst().id());
            assertEquals(1, peer.effects.get());
            assertNoActiveResources(runtime);
        }
    }

    /** Reads the actual complete registration, including adopted time and presentation. / 读取实际完整登记，包括接管时间与展示。 */
    private Map<String, String> registration(gold.debug.windowstolinux.app.main.startup.PlatformRuntime runtime,
            String id) throws Exception {
        try (var access = scope(runtime, true);
                var connection = access.openConnection();
                var query = connection
                        .prepareStatement("SELECT * FROM external_application WHERE external_application_id=?")) {
            query.setString(1, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next())
                    return Map.of();
                var result = new LinkedHashMap<String, String>();
                for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
                    result.put(rows.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT), rows.getString(i));
                assertFalse(rows.next());
                return result;
            }
        }
    }

    /** Counts original journal rows without mutating author task state. / 不改变作者任务状态地计数原账本行。 */
    private int managementCount(gold.debug.windowstolinux.app.main.startup.PlatformRuntime runtime) throws Exception {
        try (var access = scope(runtime, true);
                var connection = access.openConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_management_operation")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

}
