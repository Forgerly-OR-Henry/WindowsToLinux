package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Workflow evidence cannot become a command grant or survive its original scope. */
class DeploymentWorkflowEvidenceRegressionTest {
    private final UUID id = UUID.randomUUID();

    private final TaskContext task = task(id);

    private final ServerId server = new ServerId("fixture-server");

    @Test
    void preservesTheActualHandoffAndClosesItsOriginalHandleOnce() throws Exception {
        var closed = new AtomicInteger();
        try (var permission = open(() -> closed.incrementAndGet())) {
            var facts = DeploymentWorkflowAuthorization.reviewFacts(permission, task, command(server.id(), server.id()));
            assertEquals(id.toString(), facts.get("task"));
            assertEquals(List.of(server.id()), facts.get("serverTargets"));
            assertEquals("selected source and deployment prerequisites", facts.get("frozenWorkflowRequest"));
            assertEquals("AUTOMATIC", facts.get("approvalPolicy"));
            assertEquals(true, facts.get("hostHandoffOpened"));
            assertEquals(false, facts.get("recovery"));
            assertFalse(facts.containsKey("commandGrant"));
            permission.close();
            assertThrows(IllegalArgumentException.class,
                () -> DeploymentWorkflowAuthorization.reviewFacts(permission, task, command(server.id(), server.id())));
        }
        assertEquals(1, closed.get());
    }

    @Test
    void rejectsSubstitutedTaskServerResourceAndForgedHandle() throws Exception {
        try (var permission = open(() -> {
        })) {
            assertThrows(IllegalArgumentException.class, () -> DeploymentWorkflowAuthorization.reviewFacts(permission, task(id), command(server.id(), server.id())));
            assertThrows(IllegalArgumentException.class, () -> DeploymentWorkflowAuthorization.reviewFacts(permission, task, command("other-server", "other-server")));
            assertThrows(IllegalArgumentException.class, () -> DeploymentWorkflowAuthorization.reviewFacts(permission, task, command(server.id(), "other-server")));
            assertThrows(IllegalArgumentException.class, () -> DeploymentWorkflowAuthorization.reviewFacts(() -> {
            }, task, command(server.id(), server.id())));
        }
    }

    @Test
    void failedCleanupRevokesEvidenceButStillRetriesTheOriginalRelease() throws Exception {
        var releases = new AtomicInteger();
        var permission = open(() -> {
            if (releases.incrementAndGet() == 1)
                throw new ApiException(new Problem(ProblemCode.IO_FAILURE, "FIXTURE_RELEASE", "release failed", java.util.Map.of(), Optional.empty()));
        });
        assertThrows(ApiException.class, permission::close);
        assertThrows(IllegalArgumentException.class, () -> DeploymentWorkflowAuthorization.reviewFacts(permission, task, command(server.id(), server.id())));
        permission.close();
        permission.close();
        assertEquals(2, releases.get());
    }

    @Test
    void assistedScopeKeepsOneHandoffAndRejectsClosedOrDifferentPolicyEvidence() throws Exception {
        var ai = stub(gold.debug.windowstolinux.appapi.ai.AiService.class, (proxy, method, args) -> {
            assertEquals("configurationRevision", method.getName());
            return "model-revision";
        });
        var context = stub(ModContext.class, (proxy, method, args) -> {
            assertEquals("ai", method.getName());
            return ai;
        });
        var scope = new gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope(
            context, task, "model-revision", "AUTOMATIC", true);
        try (var permission = open(() -> {
        })) {
            scope.workflowOpened(permission);
            assertTrue(scope.facts(command(server.id(), server.id()), null, task).containsKey("workflowAuthorization"));
            assertThrows(IllegalStateException.class, () -> scope.workflowOpened(permission));
            var changedPolicy = new gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope(
                context, task, "model-revision", "AUTOMATIC", false);
            changedPolicy.workflowOpened(permission);
            assertThrows(IllegalArgumentException.class, () -> changedPolicy.facts(command(server.id(), server.id()), null, task));
            permission.close();
            assertThrows(IllegalArgumentException.class, () -> scope.verify(command(server.id(), server.id()), null, task));
        }
    }

    @Test
    void actualSourceSelectionIsImmutableAndBoundToTheOriginalActiveTaskAndServer() throws Exception {
        var scope = sourceScope("model-revision");
        var source = material(UUID.randomUUID(), "a".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> scope.sourceCaptured(server, source, task));
        try (var permission = open(() -> {
        })) {
            scope.workflowOpened(permission);
            assertThrows(IllegalArgumentException.class, () -> scope.sourceCaptured(server, source, task(id)));
            assertThrows(IllegalArgumentException.class, () -> scope.sourceCaptured(new ServerId("other"), source, task));
            assertFalse(scope.facts(command(server.id(), server.id()), null, task).containsKey("sourceSelection"));
            scope.sourceCaptured(server, source, task);
            scope.sourceCaptured(server, source, task);
            var selection = (java.util.Map<?, ?>) scope.facts(command(server.id(), server.id()), null, task).get("sourceSelection");
            assertEquals(source.snapshot().id().toString(), selection.get("snapshotId"));
            assertEquals(source.snapshot().sha256(), selection.get("sourceRevision"));
            assertEquals(1, selection.get("includedFiles"));
            assertFalse(selection.containsKey("commandGrant"));
            assertThrows(IllegalStateException.class, () -> scope.sourceCaptured(server, material(UUID.randomUUID(), "a".repeat(64)), task));
            assertThrows(IllegalStateException.class, () -> scope.sourceCaptured(server, material(source.snapshot().id(), "b".repeat(64)), task));
            permission.close();
            assertThrows(IllegalArgumentException.class, () -> scope.sourceCaptured(server, source, task));
            assertThrows(IllegalArgumentException.class, () -> scope.facts(command(server.id(), server.id()), null, task));
        }
    }

    @Test
    void sourceObservationRejectsInvalidSnapshotsAndChangedModelRoutes() throws Exception {
        var scope = sourceScope("model-revision");
        try (var permission = open(() -> {
        })) {
            scope.workflowOpened(permission);
            assertThrows(IllegalArgumentException.class, () -> scope.sourceCaptured(server, material(UUID.randomUUID(), "invalid"), task));
            var changed = sourceScope("changed-model");
            changed.workflowOpened(permission);
            assertThrows(ApiException.class, () -> changed.sourceCaptured(server, material(UUID.randomUUID(), "a".repeat(64)), task));
        }
    }

    @Test
    void archiveObservationBindsTheActualOriginalSourceAndIncludedCompanionPaths() throws Exception {
        var scope = sourceScope("model-revision");
        var original = material(UUID.randomUUID(), "a".repeat(64));
        var source = new gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial("fixture", original.snapshot(),
            List.of("project/cli/Cargo.toml", "project/native/CMakeLists.txt", "outside/private.txt"), List.of());
        var archive = archive(source, source.snapshot().id(), "a".repeat(64), "project");
        assertThrows(IllegalArgumentException.class, () -> scope.archiveCaptured(source, archive, task));
        try (var permission = open(() -> {
        })) {
            scope.workflowOpened(permission);
            scope.sourceCaptured(server, source, task);
            scope.archiveCaptured(source, archive, task);
            scope.archiveCaptured(source, archive, task);
            assertFalse(scope.effectFacts(command(server.id(), server.id()), task).containsKey("sourceArchiveObservations"));
            var intent = command(server.id(), server.id()).withSemantics(new ActionSemantics("deployment.build.run.v1",
                Set.of(new ActionTarget(server.id(), "deployment-build", "/fixture/candidate"))));
            var evidence = (java.util.Map<?, ?>) scope.effectFacts(intent, task).get("sourceArchiveObservations");
            var entries = (List<?>) evidence.get("archives");
            assertEquals(1, entries.size());
            var entry = (java.util.Map<?, ?>) entries.getFirst();
            assertEquals(source.snapshot().id().toString(), entry.get("originSnapshotId"));
            assertEquals("a".repeat(64), entry.get("sourceManifestSha256"));
            assertEquals("b".repeat(64), entry.get("archiveSha256"));
            assertEquals(List.of("cli/Cargo.toml", "native/CMakeLists.txt"), entry.get("fileNames"));
            assertEquals(2, entry.get("fileCount"));
            assertEquals(false, entry.get("fileNamesTruncated"));
            assertFalse(entry.containsKey("commandGrant"));
            assertThrows(IllegalArgumentException.class, () -> scope.archiveCaptured(source, archive, task(id)));
            assertThrows(IllegalArgumentException.class, () -> scope.archiveCaptured(source, archive(source, UUID.randomUUID(), "a".repeat(64), "project"), task));
            assertThrows(IllegalArgumentException.class, () -> scope.archiveCaptured(source, archive(source, source.snapshot().id(), "c".repeat(64), "project"), task));
            permission.close();
            assertThrows(IllegalArgumentException.class, () -> scope.archiveCaptured(source, archive, task));
        }
    }

    @Test
    void archiveFileNamesAreExplicitlyBoundedWithoutInventingACompleteListing() throws Exception {
        var scope = sourceScope("model-revision");
        var original = material(UUID.randomUUID(), "a".repeat(64));
        var source = new gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial("fixture", original.snapshot(),
            java.util.stream.IntStream.range(0, 80).mapToObj(i -> "file-" + i).toList(), List.of());
        try (var permission = open(() -> {
        })) {
            scope.workflowOpened(permission);
            scope.sourceCaptured(server, source, task);
            scope.archiveCaptured(source, archive(source, source.snapshot().id(), "a".repeat(64), ""), task);
            var intent = command(server.id(), server.id()).withSemantics(new ActionSemantics("deployment.build.run.v1",
                Set.of(new ActionTarget(server.id(), "deployment-build", "/fixture/candidate"))));
            var evidence = (java.util.Map<?, ?>) scope.effectFacts(intent, task).get("sourceArchiveObservations");
            var entry = (java.util.Map<?, ?>) ((List<?>) evidence.get("archives")).getFirst();
            assertEquals(80, entry.get("fileCount"));
            assertEquals(64, ((List<?>) entry.get("fileNames")).size());
            assertEquals(true, entry.get("fileNamesTruncated"));
        }
    }

    private static gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive archive(
        gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial source, UUID origin, String manifest, String root) {
        return new gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive(source.snapshot(), "b".repeat(64), 100, 200,
            origin, manifest, root);
    }

    private gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope sourceScope(String revision) {
        var ai = stub(gold.debug.windowstolinux.appapi.ai.AiService.class, (proxy, method, args) -> {
            assertEquals("configurationRevision", method.getName());
            return "model-revision";
        });
        var context = stub(ModContext.class, (proxy, method, args) -> {
            assertEquals("ai", method.getName());
            return ai;
        });
        return new gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope(context, task, revision, "AUTOMATIC", true);
    }

    private static gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial material(UUID id, String hash) {
        var snapshot = stub(gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot.class, (proxy, method, args) -> switch (method.getName()) {
            case "id" -> id;
            case "sha256" -> hash;
            default -> throw new AssertionError("No source bytes or live capabilities expected: " + method);
        });
        return new gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial("fixture source", snapshot, List.of("Cargo.toml"), List.of());
    }

    private WorkflowGrant open(WorkflowGrant original) throws Exception {
        var interaction = stub(InteractionService.class, (proxy, method, args) -> {
            assertEquals("openReviewedWorkflow", method.getName());
            assertSame(task, args[4]);
            assertEquals(CommandReviewMode.AUTOMATIC, args[1]);
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(original), Optional.empty());
        });
        var context = stub(ModContext.class, (proxy, method, args) -> {
            assertEquals("interaction", method.getName());
            return interaction;
        });
        return DeploymentWorkflowAuthorization.open(context, task, server, "model-revision", "AUTOMATIC", true,
            "selected source and deployment prerequisites");
    }

    private static TaskContext task(UUID id) {
        return stub(TaskContext.class, (proxy, method, args) -> {
            assertEquals("id", method.getName());
            return id;
        });
    }

    private static ActionIntent command(String server, String resourceServer) {
        return new ActionIntent("platform.ssh.execute", List.of(server), "fixture", "business-revision", "exact", "effect")
            .withSemantics(new ActionSemantics("fixture.command", Set.of(new ActionTarget(resourceServer, "directory", "/fixture"))));
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
