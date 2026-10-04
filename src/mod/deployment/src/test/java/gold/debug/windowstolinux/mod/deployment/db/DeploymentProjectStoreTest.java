package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentProjectSourceRecorder;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectSource;
import org.junit.jupiter.api.Test;

class DeploymentProjectStoreTest extends DeploymentExecutionFixture {
    private final AtomicReference<Path> selected = new AtomicReference<>();

    @Override
    protected Path selectedSourceDirectory() {
        return selected.get();
    }

    @Test
    void actualLocalSelectionSavesAndUpdatesCompleteFactsAndRestartRetainsOriginalExecution() throws Exception {
        enable();
        Path first = Files.createDirectories(root.resolve("中文 项目"));
        Path second = Files.createDirectories(root.resolve("第二项目"));
        Files.writeString(first.resolve("main.txt"), "one");
        Files.writeString(second.resolve("main.txt"), "two");
        UUID projectId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        selected.set(first);
        var old = new AtomicReference<DeploymentProjectRecord>();
        var next = new AtomicReference<DeploymentProjectRecord>();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var recorder = new DeploymentProjectSourceRecorder(context);
                var projects = new DeploymentProjectStore(context.db());
                try (var prepared = recorder.prepareLocal(task, "选择来源", projectId, "初始项目", Optional.empty(), OBSERVED,
                    1024, 10)) {
                    old.set(prepared.project());
                    assertEquals(first.toRealPath().toUri(), prepared.project().source().location());
                    assertEquals(prepared.material().snapshot().sha256(), prepared.project().source().sha256());
                    assertEquals(Optional.of(old.get()), projects.find(task, projectId));
                }
                var identity = new DeploymentExecutionIdentity(executionId, task.id(), Optional.of(projectId),
                    "application", new ServerId("server"),
                    DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "a".repeat(64));
                var journal = journal(runtime);
                journal.begin(task, identity, "deploy", List.of(intent()), Optional.of(old.get()), OBSERVED);
                assertEquals(Optional.of(old.get()),
                    journal.reviewedProject(journal.find(task, executionId).orElseThrow()));
                selected.set(second);
                try (var prepared = recorder.prepareLocal(task, "重新选择", projectId, "新项目", Optional.of(old.get()),
                    OBSERVED.plusMillis(1), 1024, 10)) {
                    next.set(prepared.project());
                    assertEquals(second.toRealPath().toUri(), next.get().source().location());
                }
                assertThrows(SQLException.class,
                    () -> projects.update(task, old.get(), new DeploymentProjectRecord(projectId, "过时写入",
                        old.get().source(), old.get().createdAt(), OBSERVED.plusMillis(2))));
                assertThrows(SQLException.class,
                    () -> projects.update(task, next.get(), new DeploymentProjectRecord(projectId, "同一毫秒",
                        next.get().source(), next.get().createdAt(), next.get().updatedAt())));
                assertThrows(SQLException.class, () -> recorder.prepareLocal(task, "冲突选择", projectId, "冲突项目",
                    Optional.of(old.get()), OBSERVED.plusMillis(2), 1024, 10));
                assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
                journal.begin(task, identity, "deploy", List.of(intent()), Optional.of(old.get()), OBSERVED);
                assertEquals(List.of(next.get()), projects.list(task, 1));
                return success();
            });
            run(runtime, task -> {
                var stale = new DeploymentExecutionIdentity(UUID.randomUUID(), task.id(), Optional.of(projectId),
                    "application", new ServerId("server"),
                    DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "a".repeat(64));
                var journal = journal(runtime);
                var failure = assertThrows(SQLException.class, () -> journal.begin(task, stale, "deploy",
                    List.of(intent()), Optional.of(old.get()), OBSERVED.plusMillis(2)));
                assertEquals("Project changed after source review", failure.getMessage());
                assertTrue(journal.find(task, stale.executionId()).isEmpty());
                return success();
            });
            valid(runtime);
        }
        Files.delete(first.resolve("main.txt"));
        Files.delete(first);
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var projects = new DeploymentProjectStore(runtime.modContexts().get(OWNER).context().db());
                assertEquals(Optional.of(next.get()), projects.find(task, projectId));
                assertEquals(Optional.of(old.get()),
                    journal(runtime).reviewedProject(journal(runtime).find(task, executionId).orElseThrow()));
                assertThrows(SQLException.class, () -> projects.list(task, 1001));
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void projectCommitAcknowledgementUnknownCanBeReadByOriginalIdWithoutReplaying() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                UUID id = UUID.randomUUID();
                var source = new DeploymentProjectSource(DeploymentProjectSource.KindType.LOCAL,
                    root.toRealPath().toUri(), Optional.empty(), Optional.empty(), "a".repeat(64));
                var project = new DeploymentProjectRecord(id, "已冻结", source, OBSERVED, OBSERVED);
                var fault = new DeploymentProjectStore(commitFault(context.db()));
                var failure = assertThrows(ApiException.class, () -> fault.create(task, project));
                assertEquals("DEPLOYMENT_COMMIT_UNKNOWN", failure.problem().eventCode());
                var projects = new DeploymentProjectStore(context.db());
                assertEquals(Optional.of(project), projects.find(task, id));
                projects.create(task, project);
                assertThrows(SQLException.class,
                    () -> projects.create(task, new DeploymentProjectRecord(id, "冲突", source, OBSERVED, OBSERVED)));
                sql(runtime, task, "UPDATE deployment_project SET source_sha256=NULL WHERE project_id='" + id + "'");
                try (var scope = scope(runtime, true)) {
                    assertFalse(new DeploymentSchema().validate(scope).valid());
                }
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_project WHERE project_id='" + id
                    + "' AND source_sha256 IS NULL"));
                sql(runtime, task, "UPDATE deployment_project SET source_sha256='" + source.sha256()
                    + "' WHERE project_id='" + id + "'");
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void gitReferenceFactsDistinguishKindsAndRejectCommitMismatch() {
        var remote = java.net.URI.create("https://example.test/repo.git");
        var sha = "a".repeat(64);
        var commit = "b".repeat(40);
        var branch = new DeploymentProjectSource(DeploymentProjectSource.KindType.GIT, remote,
            Optional.of("BRANCH:main"), Optional.of(commit), sha);
        var tag = new DeploymentProjectSource(DeploymentProjectSource.KindType.GIT, remote, Optional.of("TAG:main"),
            Optional.of(commit), sha);
        assertNotEquals(branch, tag);
        assertThrows(IllegalArgumentException.class,
            () -> new DeploymentProjectSource(DeploymentProjectSource.KindType.GIT, remote,
                Optional.of("COMMIT:" + "c".repeat(40)), Optional.of(commit), sha));
        assertThrows(IllegalArgumentException.class,
            () -> new DeploymentProjectSource(DeploymentProjectSource.KindType.GIT, remote,
                Optional.of("BRANCH: main"), Optional.of(commit), sha));
    }
}
