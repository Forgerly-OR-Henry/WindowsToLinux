package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.db.schema.SchemaProvisioner;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformModIntentsTest {
    @TempDir
    Path root;

    static final String P = "example.provider", C = "example.consumer", G = "example.grandchild", FREE = "example.free";

    static final ObjectMapper JSON = new ObjectMapper();

    PlatformRuntime runtime() throws Exception {
        var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION);
        if (runtime.security().securityState().configured())
            runtime.security().unlock(PASSWORD.toCharArray());
        else
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        runtime.started();
        return runtime;
    }

    static PlatformModIntents.Choice choice(boolean enabled, String version) {
        return new PlatformModIntents.Choice(enabled, Optional.ofNullable(version));
    }

    static String manifest(String owner, String version, Map<String, String> dependencies, String schema,
                           List<Map<String, String>> migrations) throws Exception {
        var node = JSON.createObjectNode().put("formatVersion", 1).put("id", owner).put("name", "Fixture")
            .put("version", version).put("platformApiRange", "1.0.0").put("minJavaVersion", 21)
            .put("mainJar", "main/fixture.jar").put("entryClass", owner + ".Mod");
        node.set("dependencies", JSON.valueToTree(new TreeMap<>(dependencies).entrySet().stream()
            .map(e -> Map.of("id", e.getKey(), "versionRange", e.getValue())).toList()));
        if (schema != null)
            node.put("schemaVersion", schema);
        if (!migrations.isEmpty())
            node.set("migrations", JSON.valueToTree(migrations));
        return node.toString();
    }

    static void release(PlatformRuntime runtime, String owner, String version, Map<String, String> dependencies,
                        String schema, List<Map<String, String>> migrations) throws Exception {
        sql(runtime.persistence().database(),
            "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,schema_version,manifest_json,origin_kind,installed_at,state) VALUES(?,?,'fixture',?, ?,?,'CLASS',1,'READY')",
            owner, version, "0".repeat(64), schema, manifest(owner, version, dependencies, schema, migrations));
    }

    static void add(PlatformRuntime runtime, String owner, boolean enabled, Map<String, String> dependencies,
                    String schema) throws Exception {
        var db = runtime.persistence().database();
        sql(db, "INSERT INTO mod_registry(mod_id,updated_at) VALUES(?,1)", owner);
        release(runtime, owner, "1.0.0", dependencies, schema, List.of());
        sql(db, "UPDATE mod_registry SET enabled=?,selected_version='1.0.0',selection_initialized=TRUE,last_boot_id=?,last_runtime_state=?,actual_version=? WHERE mod_id=?",
            enabled, db.bootId(), enabled ? "RUNNING" : "STOPPED", enabled ? "1.0.0" : null, owner);
    }

    static void graph(PlatformRuntime runtime) throws Exception {
        add(runtime, P, true, Map.of(), null);
        add(runtime, C, true, Map.of(P, "[1.0.0,2.0.0)"), null);
        add(runtime, G, true, Map.of(C, "1.0.0"), null);
        add(runtime, FREE, true, Map.of(), null);
    }

    static String text(PlatformRuntime runtime, String query) throws Exception {
        try (var c = runtime.persistence().database().openConnection();
             var s = c.createStatement();
             var r = s.executeQuery(query)) {
            assertTrue(r.next());
            return r.getString(1);
        }
    }

    static void enabled(PlatformRuntime runtime, int expected) throws Exception {
        assertEquals(expected,
            count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry WHERE enabled"));
    }

    static TaskRequest work() {
        return new TaskRequest("fixture", "Mod intent local check", List.of(), true);
    }

    @Test
    void enableSavesIntentAndPendingOperationWithoutPretendingToRunOrReplay() throws Exception {
        try (var runtime = runtime()) {
            add(runtime, P, false, Map.of(), null);
            var manager = runtime.modIntents();
            var review = manager.review(P, choice(true, "1.0.0"), Optional.empty());
            assertEquals(Set.of(P), review.affected());
            assertEquals(review.id(), manager.apply(review, false));
            enabled(runtime, 1);
            assertEquals("STOPPED", text(runtime, "SELECT last_runtime_state FROM mod_registry"));
            assertNull(text(runtime, "SELECT actual_version FROM mod_registry"));
            assertNull(text(runtime, "SELECT disable_ack_revision FROM mod_registry"));
            assertEquals(1, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM mod_operation WHERE state='WAITING' AND phase='WAIT_RESTART' AND operation_type='ENABLE' AND accepted_revision=1"));
            assertEquals("MOD_REVIEW_USED",
                assertThrows(ApiException.class, () -> manager.apply(review, false)).problem().eventCode());
            assertThrows(ApiException.class,
                () -> runtime.tasks().bind(P, "1.0.0", false).submit(work(), t -> success(null)));
        }
    }

    @Test
    void cascadeNeedsExactConfirmationAndDoesNotCancelAlreadyAcceptedWork() throws Exception {
        try (var runtime = runtime()) {
            graph(runtime);
            var manager = runtime.modIntents();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var binding = runtime.tasks().bind(C, "1.0.0", false);
            var task = binding.submit(work(), t -> {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return success("original");
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var review = manager.review(P, choice(false, "1.0.0"), Optional.empty());
                assertEquals(Set.of(C, G), review.cascade());
                assertEquals("MOD_CASCADE_CONFIRMATION_REQUIRED",
                    assertThrows(ApiException.class, () -> manager.apply(review, false)).problem().eventCode());
                enabled(runtime, 4);
                assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
                manager.apply(review, true);
                enabled(runtime, 1);
                assertEquals(3, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
                assertEquals(TaskState.RUNNING, task.snapshot().state());
                assertEquals("MOD_RESTART_PENDING",
                    assertThrows(ApiException.class, () -> binding.submit(work(), t -> success(null))).problem()
                        .eventCode());
                assertEquals(TaskResult.SUCCEEDED,
                    runtime.tasks().bind(FREE, "1.0.0", false).submit(work(), t -> success(null)).completion()
                        .toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
            } finally {
                release.countDown();
            }
            assertEquals("original",
                task.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).value().orElseThrow());
        }
    }

    @Test
    void compatibleSwitchSealsDependentAdmissionAndCancellationRestoresItAtomically() throws Exception {
        try (var runtime = runtime()) {
            graph(runtime);
            release(runtime, P, "1.1.0", Map.of(), null, List.of());
            var manager = runtime.modIntents();
            var review = manager.review(P, choice(true, "1.1.0"), Optional.empty());
            assertTrue(review.cascade().isEmpty());
            assertEquals(Set.of(P, C, G), review.affected());
            manager.apply(review, false);
            enabled(runtime, 4);
            assertEquals("1.0.0", text(runtime, "SELECT actual_version FROM mod_registry WHERE mod_id='" + P + "'"));
            assertThrows(ApiException.class,
                () -> runtime.tasks().bind(G, "1.0.0", false).submit(work(), t -> success(null)));
            String archive = text(runtime, "SELECT archive_log_id FROM mod_operation");
            manager.apply(manager.reviewCancellation(review.id()), false);
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals("1.0.0", text(runtime, "SELECT selected_version FROM mod_registry WHERE mod_id='" + P + "'"));
            assertEquals(1, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM runtime_log WHERE outcome='CANCELLED' AND log_id='" + archive + "'"));
            assertEquals(TaskResult.SUCCEEDED, runtime.tasks().bind(G, "1.0.0", false).submit(work(), t -> success(null))
                .completion().toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
            assertEquals("MOD_REQUEST_NOT_PENDING",
                assertThrows(ApiException.class, () -> manager.reviewCancellation(review.id())).problem()
                    .eventCode());
        }
    }

    @Test
    void incompatibleSwitchListsTransitiveDisablementButKeepsTheirVersionChoices() throws Exception {
        try (var runtime = runtime()) {
            graph(runtime);
            release(runtime, P, "2.0.0", Map.of(), null, List.of());
            var manager = runtime.modIntents();
            var review = manager.review(P, choice(true, "2.0.0"), Optional.empty());
            assertEquals(Set.of(C, G), review.cascade());
            manager.apply(review, true);
            enabled(runtime, 2);
            assertEquals("2.0.0", text(runtime, "SELECT selected_version FROM mod_registry WHERE mod_id='" + P + "'"));
            assertEquals("1.0.0", text(runtime, "SELECT selected_version FROM mod_registry WHERE mod_id='" + C + "'"));
            manager.apply(manager.reviewCancellation(review.id()), false);
            enabled(runtime, 4);
        }
    }

    static void optionalDependency(PlatformRuntime runtime, String owner) throws Exception {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(
            text(runtime, "SELECT manifest_json FROM mod_version WHERE mod_id='" + owner + "'"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("dependencies").get(0)).put("optional", true);
        sql(runtime.persistence().database(), "UPDATE mod_version SET manifest_json=? WHERE mod_id=?", node.toString(),
            owner);
    }

    @Test
    void disablingOptionalProviderPreservesConsumersButSealsTheirBindingsUntilRestart() throws Exception {
        try (var runtime = runtime()) {
            graph(runtime);
            optionalDependency(runtime, C);
            var manager = runtime.modIntents();
            var review = manager.review(P, choice(false, "1.0.0"), Optional.empty());
            assertTrue(review.cascade().isEmpty());
            assertEquals(Set.of(P, C, G), review.affected());
            manager.apply(review, false);
            enabled(runtime, 3);
            assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals("MOD_RESTART_PENDING", assertThrows(ApiException.class,
                () -> runtime.tasks().bind(C, "1.0.0", false).submit(work(), t -> success(null))).problem().eventCode());
            manager.apply(manager.reviewCancellation(review.id()), false);
            enabled(runtime, 4);
        }
    }

    @Test
    void consumerCanBeEnabledWithMissingOptionalProvider() throws Exception {
        try (var runtime = runtime()) {
            add(runtime, C, false, Map.of(P, "1.0.0"), null);
            optionalDependency(runtime, C);
            var manager = runtime.modIntents();
            var review = manager.review(C, choice(true, "1.0.0"), Optional.empty());
            assertTrue(review.cascade().isEmpty());
            assertEquals(Set.of(C), review.affected());
            manager.apply(review, false);
            enabled(runtime, 1);
        }
    }

    @Test
    void failedOptionalProviderDoesNotBlockConsumerEnablement() throws Exception {
        try (var runtime = runtime()) {
            add(runtime, P, true, Map.of(), null);
            add(runtime, C, false, Map.of(P, "1.0.0"), null);
            optionalDependency(runtime, C);
            sql(runtime.persistence().database(), "UPDATE mod_registry SET last_runtime_state='FAILED' WHERE mod_id=?", P);
            var manager = runtime.modIntents();
            var review = manager.review(C, choice(true, "1.0.0"), Optional.empty());
            assertTrue(review.cascade().isEmpty());
            manager.apply(review, false);
            enabled(runtime, 2);
        }
    }

    @Test
    void missingFailedDependenciesAndStaleReviewsNeverSaveRejectedIntent() throws Exception {
        try (var runtime = runtime()) {
            add(runtime, P, false, Map.of(), null);
            add(runtime, C, false, Map.of(P, "1.0.0"), null);
            var manager = runtime.modIntents();
            assertThrows(ApiException.class, () -> manager.review(C, choice(true, "1.0.0"), Optional.empty()));
            enabled(runtime, 0);
            sql(runtime.persistence().database(),
                "UPDATE mod_registry SET enabled=TRUE,last_runtime_state='FAILED' WHERE mod_id=?", P);
            assertEquals("MOD_DEPENDENCY_NOT_RUNNING",
                assertThrows(ApiException.class, () -> manager.review(C, choice(true, "1.0.0"), Optional.empty()))
                    .problem().eventCode());
            var disable = manager.review(P, choice(false, "1.0.0"), Optional.empty());
            assertTrue(disable.cascade().isEmpty());
            sql(runtime.persistence().database(), "UPDATE mod_registry SET revision=revision+1 WHERE mod_id=?", P);
            assertEquals("MOD_REVIEW_STALE",
                assertThrows(ApiException.class, () -> manager.apply(disable, false)).problem().eventCode());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            enabled(runtime, 1);
        }
    }

    @Test
    void lateChoiceWriteFailureRollsBackTheWholeCascadeAndDoesNotReplayReview() throws Exception {
        try (var runtime = runtime()) {
            graph(runtime);
            var db = runtime.persistence().database();
            var manager = runtime.modIntents();
            var review = manager.review(P, choice(false, "1.0.0"), Optional.empty());
            sql(db, "ALTER TABLE mod_registry ADD CONSTRAINT fixture_choice CHECK(mod_id<>'example.provider' OR enabled)");
            try {
                assertEquals(ProblemCode.UNKNOWN,
                    assertThrows(ApiException.class, () -> manager.apply(review, true)).problem().code());
                enabled(runtime, 4);
                assertEquals(0, count(db, "SELECT COUNT(*) FROM mod_registry WHERE revision<>0"));
                assertEquals(0, count(db, "SELECT COUNT(*) FROM mod_operation"));
                assertThrows(ApiException.class, () -> manager.apply(review, true));
            } finally {
                sql(db, "ALTER TABLE mod_registry DROP CONSTRAINT fixture_choice");
            }
            manager.apply(manager.review(P, choice(false, "1.0.0"), Optional.empty()), true);
            enabled(runtime, 1);
        }
    }

    @Test
    void lateArchiveFailureRollsBackCancellationAndOriginalOperationIdsRemainRecoverable() throws Exception {
        try (var runtime = runtime()) {
            graph(runtime);
            var db = runtime.persistence().database();
            var manager = runtime.modIntents();
            var request = manager.review(P, choice(false, "1.0.0"), Optional.empty());
            manager.apply(request, true);
            var cancel = manager.reviewCancellation(request.id());
            sql(db, "ALTER TABLE runtime_log ADD CONSTRAINT fixture_archive CHECK(category<>'mod_operation' OR subject_mod_id<>'example.provider')");
            try {
                assertEquals(ProblemCode.UNKNOWN,
                    assertThrows(ApiException.class, () -> manager.apply(cancel, false)).problem().code());
                enabled(runtime, 1);
                assertEquals(3, count(db, "SELECT COUNT(*) FROM mod_operation"));
                assertEquals(0, count(db, "SELECT COUNT(*) FROM runtime_log WHERE category='mod_operation'"));
            } finally {
                sql(db, "ALTER TABLE runtime_log DROP CONSTRAINT fixture_archive");
            }
            manager.apply(manager.reviewCancellation(request.id()), false);
            enabled(runtime, 4);
            assertEquals(0, count(db, "SELECT COUNT(*) FROM mod_operation"));
            assertEquals(3, count(db, "SELECT COUNT(*) FROM runtime_log WHERE outcome='CANCELLED'"));
        }
    }

    static SchemaIdentity schema(PlatformRuntime runtime, String owner, String version) throws Exception {
        var identity = new SchemaIdentity(owner, version, UUID.randomUUID());
        var names = new SchemaCoordinates(identity);
        var db = runtime.persistence().database();
        sql(db, "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at) VALUES(?,?,?,?,?,'PREPARING',1)",
            owner, version, identity.generation(), names.physicalName(), names.databaseUser());
        new SchemaProvisioner(db).create(identity);
        sql(db, "UPDATE mod_schema SET state='READY',validated_at=1 WHERE mod_id=? AND schema_version=?", owner,
            version);
        return identity;
    }

    static Map<String, String> edge(String from, String to) {
        return Map.of("from", from, "to", to, "class", P + ".Move" + from + to);
    }

    @Test
    void migrationRequiresExplicitExactSourceAndUniquePathWhileReadyTargetIsReused() throws Exception {
        try (var runtime = runtime()) {
            add(runtime, P, true, Map.of(), "1");
            var source = schema(runtime, P, "1");
            release(runtime, P, "2.0.0", Map.of(), "2", List.of(edge("1", "2")));
            release(runtime, P, "3.0.0", Map.of(), "3", List.of(edge("1", "2"), edge("2", "3"), edge("1", "3")));
            var manager = runtime.modIntents();
            assertEquals("MOD_SOURCE_NOT_APPLICABLE", assertThrows(ApiException.class,
                () -> manager.review(P, choice(true, "1.0.0"), Optional.of(source))).problem().eventCode());
            assertEquals("MOD_MIGRATION_SOURCE_REQUIRED",
                assertThrows(ApiException.class, () -> manager.review(P, choice(true, "2.0.0"), Optional.empty()))
                    .problem().eventCode());
            assertThrows(ApiException.class, () -> manager.review(P, choice(true, "2.0.0"),
                Optional.of(new SchemaIdentity(P, "1", UUID.randomUUID()))));
            assertEquals("MOD_MIGRATION_PATH_INVALID", assertThrows(ApiException.class,
                () -> manager.review(P, choice(true, "3.0.0"), Optional.of(source))).problem().eventCode());
            var stale = manager.review(P, choice(true, "2.0.0"), Optional.of(source));
            sql(runtime.persistence().database(), "UPDATE mod_schema SET generation=? WHERE mod_id=?",
                UUID.randomUUID(), P);
            assertEquals("MOD_REVIEW_STALE",
                assertThrows(ApiException.class, () -> manager.apply(stale, false)).problem().eventCode());
            sql(runtime.persistence().database(), "UPDATE mod_schema SET generation=? WHERE mod_id=?",
                source.generation(), P);
            var accepted = manager.review(P, choice(true, "2.0.0"), Optional.of(source));
            sql(runtime.persistence().database(), "UPDATE mod_schema SET validation_digest=? WHERE mod_id=?",
                "1".repeat(64), P);
            assertEquals("MOD_REVIEW_STALE",
                assertThrows(ApiException.class, () -> manager.apply(accepted, false)).problem().eventCode());
            var current = manager.review(P, choice(true, "2.0.0"), Optional.of(source));
            manager.apply(current, false);
            var request = JSON.readTree(text(runtime, "SELECT request_json FROM mod_operation"));
            assertEquals("MIGRATE", request.path("data").path("mode").asText());
            assertEquals(source.generation().toString(), request.path("data").path("generation").asText());
            assertEquals("1", text(runtime, "SELECT source_schema_version FROM mod_operation"));
            assertEquals("2", text(runtime, "SELECT target_schema_version FROM mod_operation"));
            assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema"));
            manager.apply(manager.reviewCancellation(current.id()), false);
            schema(runtime, P, "2");
            assertThrows(ApiException.class, () -> manager.review(P, choice(true, "2.0.0"), Optional.of(source)));
            manager.apply(manager.review(P, choice(true, "2.0.0"), Optional.empty()), false);
            assertEquals("REUSE", JSON.readTree(text(runtime, "SELECT request_json FROM mod_operation")).path("data")
                .path("mode").asText());
        }
    }

    @Test
    void cancellationPreservesAnIndependentLaterDisableRequest() throws Exception {
        try (var runtime = runtime()) {
            graph(runtime);
            release(runtime, P, "1.1.0", Map.of(), null, List.of());
            var manager = runtime.modIntents();
            var switched = manager.review(P, choice(true, "1.1.0"), Optional.empty());
            manager.apply(switched, false);
            var independent = manager.review(C, choice(false, "1.0.0"), Optional.empty());
            manager.apply(independent, true);
            manager.apply(manager.reviewCancellation(switched.id()), false);
            enabled(runtime, 2);
            assertEquals("1.0.0", text(runtime, "SELECT selected_version FROM mod_registry WHERE mod_id='" + P + "'"));
            assertEquals(2, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals(Set.of(C, G), manager.reviewCancellation(independent.id()).choices().keySet());
        }
    }

    @Test
    void pendingRequestSurvivesDatabaseReopenWithoutBeingAppliedOrRecreated() throws Exception {
        UUID request;
        String operationId;
        try (var runtime = runtime()) {
            add(runtime, P, false, Map.of(), null);
            var manager = runtime.modIntents();
            var review = manager.review(P, choice(true, "1.0.0"), Optional.empty());
            request = manager.apply(review, false);
            operationId = text(runtime, "SELECT operation_id FROM mod_operation");
        }
        try (var runtime = runtime()) {
            assertEquals(operationId, text(runtime, "SELECT operation_id FROM mod_operation"));
            assertEquals("WAIT_RESTART", text(runtime, "SELECT phase FROM mod_operation"));
            assertEquals("STOPPED", text(runtime, "SELECT last_runtime_state FROM mod_registry"));
            var manager = runtime.modIntents();
            manager.apply(manager.reviewCancellation(request), false);
            enabled(runtime, 0);
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        }
    }

    @Test
    void lockedManagementAndUnverifiablePendingScopeRejectNewWorkWithoutDiscardingIntent() throws Exception {
        try (var runtime = runtime()) {
            graph(runtime);
            var manager = runtime.modIntents();
            runtime.security().lock();
            assertThrows(Exception.class, () -> manager.review(P, choice(false, "1.0.0"), Optional.empty()));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            runtime.security().unlock(PASSWORD.toCharArray());
            var review = manager.review(P, choice(false, "1.0.0"), Optional.empty());
            manager.apply(review, true);
            sql(runtime.persistence().database(), "UPDATE mod_operation SET request_json='{}' WHERE mod_id=?", P);
            assertEquals("MOD_INTENT_UNVERIFIED",
                assertThrows(ApiException.class,
                    () -> runtime.tasks().bind(FREE, "1.0.0", false).submit(work(), t -> success(null)))
                    .problem().eventCode());
            assertEquals("MOD_PENDING_INVALID",
                assertThrows(ApiException.class, () -> manager.reviewCancellation(review.id())).problem()
                    .eventCode());
            assertEquals(3, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        }
    }

    @Test
    void firstSchemaChoiceOnlySchedulesInitializationAndNoopDoesNotCreateRestartWork() throws Exception {
        try (var runtime = runtime()) {
            add(runtime, P, false, Map.of(), "7");
            var manager = runtime.modIntents();
            manager.apply(manager.review(P, choice(false, "1.0.0"), Optional.empty()), false);
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            manager.apply(manager.review(P, choice(true, "1.0.0"), Optional.empty()), false);
            assertEquals("INITIALIZE", JSON.readTree(text(runtime, "SELECT request_json FROM mod_operation"))
                .path("data").path("mode").asText());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema"));
        }
    }
}
