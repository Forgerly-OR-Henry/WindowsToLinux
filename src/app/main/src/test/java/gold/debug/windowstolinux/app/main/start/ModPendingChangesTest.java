package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.sql;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModPendingChangesTest {
    @TempDir
    Path root;

    private PlatformRuntime open() throws Exception {
        var fixture = new PlatformModIntentsTest();
        fixture.root = root;
        return fixture.runtime();
    }

    @Test
    void releaseMetadataComesFromEachExactManifestIncludingOptionalDependencies() throws Exception {
        try (var runtime = open()) {
            add(runtime, C, false, Map.of(P, "[1.0.0,2.0.0)"), null);
            var original = (ObjectNode) JSON.readTree(text(runtime, "SELECT manifest_json FROM mod_version WHERE mod_id='" + C + "'"));
            original.put("description", "示例基础能力及可选增强");
            original.withArray("dependencies").addObject().put("id", FREE).put("versionRange", "2.0.0").put("optional", true);
            sql(runtime.persistence().database(), "UPDATE mod_version SET manifest_json=? WHERE mod_id=?",
                original.toString(), C);
            release(runtime, C, "2.0.0", Map.of(P, "[2.0.0,3.0.0)"), null, List.of());
            var versions = runtime.modIntents().list().getFirst().versions();
            assertEquals(List.of("2.0.0", "1.0.0"), versions.stream().map(value -> value.manifest().version()).toList());
            var first = versions.get(1).manifest();
            assertEquals(Optional.of("示例基础能力及可选增强"), first.description());
            assertEquals(List.of(new PlatformModIntents.DependencyInfo(P, "[1.0.0,2.0.0)", false),
                new PlatformModIntents.DependencyInfo(FREE, "2.0.0", true)), first.dependencies());
            assertEquals(List.of(new PlatformModIntents.DependencyInfo(P, "[2.0.0,3.0.0)", false)),
                versions.getFirst().manifest().dependencies());
            assertThrows(UnsupportedOperationException.class, () -> first.dependencies().clear());
        }
    }

    @Test
    void pendingDifferencesUseSavedBaseRatherThanActualAndKeepItAcrossRepeatedSaves() throws Exception {
        try (var runtime = open()) {
            add(runtime, P, true, Map.of(), null);
            release(runtime, P, "1.1.0", Map.of(), null, List.of());
            release(runtime, P, "1.2.0", Map.of(), null, List.of());
            sql(runtime.persistence().database(), "UPDATE mod_registry SET actual_version='0.9.0' WHERE mod_id=?", P);
            var service = runtime.modIntents();
            var first = service.review(P, choice(true, "1.1.0"), Optional.empty());
            service.apply(first, false);
            var firstChange = service.pendingChanges().getFirst();
            assertEquals(choice(true, "1.0.0"), firstChange.before());
            assertEquals(choice(true, "1.1.0"), firstChange.after());
            assertEquals(first.id(), firstChange.requestId());
            assertEquals("", firstChange.preparation());
            var second = service.review(P, choice(true, "1.2.0"), Optional.empty());
            service.apply(second, false);
            var change = service.pendingChanges().getFirst();
            assertEquals(choice(true, "1.0.0"), change.before());
            assertEquals(choice(true, "1.2.0"), change.after());
            assertEquals(second.id(), change.requestId());
            assertNotEquals(firstChange.operationId(), change.operationId());
            assertEquals("0.9.0", service.list().getFirst().actual().orElseThrow());
            assertThrows(UnsupportedOperationException.class, () -> service.pendingChanges().clear());
            service.apply(service.review(P, choice(true, "1.0.0"), Optional.empty()), false);
            assertTrue(service.pendingChanges().isEmpty());
        }
    }

    @Test
    void everyLinkedModHasItsOwnRowAndWholeGroupCancellationPreservesIndependentRequests() throws Exception {
        try (var runtime = open()) {
            graph(runtime);
            var service = runtime.modIntents();
            var group = service.review(P, choice(false, "1.0.0"), Optional.empty());
            service.apply(group, true);
            var independent = service.review(FREE, choice(false, "1.0.0"), Optional.empty());
            service.apply(independent, false);
            var changes = service.pendingChanges();
            assertEquals(4, changes.size());
            assertEquals(Set.of(P, C, G, FREE), changes.stream().map(PlatformModIntents.PendingChange::owner).collect(java.util.stream.Collectors.toSet()));
            for (var change : changes) {
                assertEquals(choice(true, "1.0.0"), change.before());
                assertEquals(choice(false, "1.0.0"), change.after());
                assertEquals("", change.preparation());
                if (change.owner().equals(FREE)) {
                    assertEquals(independent.id(), change.requestId());
                    assertEquals(List.of(FREE), change.affected());
                } else {
                    assertEquals(group.id(), change.requestId());
                    assertEquals(Set.of(P, C, G), Set.copyOf(change.affected()));
                }
            }
            var consumer = changes.stream().filter(value -> value.owner().equals(C)).findFirst().orElseThrow();
            service.apply(service.reviewCancellation(consumer.requestId()), false);
            var retained = service.pendingChanges();
            assertEquals(1, retained.size());
            assertEquals(FREE, retained.getFirst().owner());
            assertEquals(independent.id(), retained.getFirst().requestId());
            service.apply(service.reviewCancellation(independent.id()), false);
            assertTrue(service.pendingChanges().isEmpty());
        }
    }

    @Test
    void sameChoiceFirstPreparationRemainsVisibleAndCanBeCancelledWithoutChangingTheChoice() throws Exception {
        try (var runtime = open()) {
            add(runtime, P, true, Map.of(), "1");
            sql(runtime.persistence().database(), "UPDATE mod_registry SET actual_version=NULL,last_runtime_state='STOPPED' WHERE mod_id=?", P);
            var service = runtime.modIntents();
            var request = service.apply(service.reviewPreparation(P, Optional.empty()), false);
            var change = service.pendingChanges().getFirst();
            assertEquals(choice(true, "1.0.0"), change.before());
            assertEquals(change.before(), change.after());
            assertEquals("INITIALIZE · 1", change.preparation());
            service.apply(service.reviewCancellation(request), false);
            assertTrue(service.pendingChanges().isEmpty());
            assertEquals(choice(true, "1.0.0"), service.list().getFirst().choice());
        }
    }

    @Test
    void migrationAndReuseSummariesMatchTheExactSelectedDataIdentity() throws Exception {
        try (var runtime = open()) {
            add(runtime, P, true, Map.of(), "1");
            var source = schema(runtime, P, "1");
            release(runtime, P, "2.0.0", Map.of(), "2", List.of(edge("1", "2")));
            var service = runtime.modIntents();
            var request = service.apply(service.review(P, choice(true, "2.0.0"), Optional.of(source)), false);
            assertEquals("MIGRATE · 1 → 2", service.pendingChanges().getFirst().preparation());
            service.apply(service.reviewCancellation(request), false);
            schema(runtime, P, "2");
            service.apply(service.review(P, choice(true, "2.0.0"), Optional.empty()), false);
            assertEquals("REUSE · 2", service.pendingChanges().getFirst().preparation());
        }
    }

    @Test
    void unknownOperationsAreNotPresentedAsReversiblePendingChoicesAndLockRevokesReads() throws Exception {
        try (var runtime = open()) {
            add(runtime, P, false, Map.of(), null);
            var service = runtime.modIntents();
            var request = service.apply(service.review(P, choice(true, "1.0.0"), Optional.empty()), false);
            sql(runtime.persistence().database(), "UPDATE mod_operation SET state='UNKNOWN',phase='APPLYING' WHERE mod_id=?", P);
            assertTrue(service.pendingChanges().isEmpty());
            assertTrue(service.list().getFirst().operations().getFirst().request().isEmpty());
            assertEquals("MOD_REQUEST_NOT_PENDING", assertThrows(ApiException.class,
                () -> service.reviewCancellation(request)).problem().eventCode());
            runtime.security().lock();
            assertThrows(Exception.class, service::pendingChanges);
        }
    }

    @Test
    void pendingViewRejectsMalformedOrMismatchedSelectedManifestWithoutWriting() throws Exception {
        try (var runtime = open()) {
            add(runtime, P, false, Map.of(), null);
            var service = runtime.modIntents();
            service.apply(service.review(P, choice(true, "1.0.0"), Optional.empty()), false);
            String request = text(runtime, "SELECT request_json FROM mod_operation");
            String original = text(runtime, "SELECT manifest_json FROM mod_version");
            var wrong = (ObjectNode) JSON.readTree(original);
            wrong.put("id", "example.other");
            for (String invalid : List.of("not-json", wrong.toString())) {
                sql(runtime.persistence().database(), "UPDATE mod_version SET manifest_json=? WHERE mod_id=?", invalid, P);
                assertEquals("MOD_MANIFEST_INVALID", assertThrows(ApiException.class, service::pendingChanges).problem().eventCode());
                assertEquals("MOD_MANIFEST_INVALID", assertThrows(ApiException.class, service::list).problem().eventCode());
                assertEquals(request, text(runtime, "SELECT request_json FROM mod_operation"));
            }
            sql(runtime.persistence().database(), "UPDATE mod_version SET manifest_json=? WHERE mod_id=?", original, P);
            assertEquals(1, service.pendingChanges().size());
        }
    }

    @Test
    void pendingViewRejectsForgedPreparationInsteadOfRenderingLooseJsonAsFacts() throws Exception {
        try (var runtime = open()) {
            add(runtime, P, false, Map.of(), "1");
            var service = runtime.modIntents();
            service.apply(service.review(P, choice(true, "1.0.0"), Optional.empty()), false);
            String request = text(runtime, "SELECT request_json FROM mod_operation");
            var invalidData = List.of(
                "{}", "[]", "{\"mode\":\"NONE\"}", "{\"mode\":\"INITIALIZE\",\"target\":2}",
                "{\"mode\":\"INITIALIZE\",\"target\":\"2\"}",
                "{\"mode\":\"INITIALIZE\",\"target\":\"1\",\"extra\":true}",
                "{\"mode\":\"REUSE\",\"target\":\"1\",\"generation\":\"" + UUID.randomUUID() + "\"}");
            for (String invalid : invalidData) {
                var forged = (ObjectNode) JSON.readTree(request);
                forged.set("data", JSON.readTree(invalid));
                sql(runtime.persistence().database(), "UPDATE mod_operation SET request_json=? WHERE mod_id=?", forged.toString(), P);
                assertEquals("MOD_PENDING_INVALID", assertThrows(ApiException.class, service::pendingChanges).problem().eventCode(), invalid);
                assertEquals(forged.toString(), text(runtime, "SELECT request_json FROM mod_operation"));
            }
            sql(runtime.persistence().database(), "UPDATE mod_operation SET request_json=? WHERE mod_id=?", request, P);
            assertEquals("INITIALIZE · 1", service.pendingChanges().getFirst().preparation());
        }
    }
}
