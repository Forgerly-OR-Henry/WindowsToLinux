package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.server.ReferenceResolution;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Verifies whole-graph retained compare-and-set, preserved originals and rollback. / 验证整图保留比较更新、原数据保留与回滚。 */
class DeploymentRetainedReleaseRepositoryTest extends DeploymentExecutionFixture {
    private DeploymentReleaseRepository releases(PlatformRuntime runtime) {
        return new DeploymentReleaseRepository(runtime.modContexts().get(OWNER).context().db());
    }

    private DeploymentReleaseRecord commit(PlatformRuntime runtime, TaskContext task, DeploymentApplicationGraph graph,
                                           int revision) throws Exception {
        UUID id = UUID.randomUUID();
        var reference = prepare(runtime, task, id);
        var journal = journal(runtime);
        journal.commitApplication(task, id, graph, publications(graph, revision, List.of()), OBSERVED.plusSeconds(10));
        var link = DeploymentExecutionCodec.release(journal.find(task, id).orElseThrow()).orElseThrow();
        runtime.modContexts().get(OWNER).context().servers().resolve(reference.id(), ReferenceResolution.REFERENCED, task);
        journal.finishCleanup(task, id, OBSERVED.plusSeconds(11));
        return releases(runtime).find(task, graph.applicationId(), link.id()).orElseThrow();
    }

    private DeploymentApplicationGraph changedGraph() {
        return new DeploymentApplicationGraph("application", "worker", new DeploymentHealthCheck.Tcp(9090, 30, 3),
            List.of(component("worker", "server", List.of()), component("web", "server", List.of())));
    }

    @Test
    void switchesCompleteGraphPreservesOriginalAndRejectsChangedActiveReview() throws Exception {
        enable();
        var first = new AtomicReference<DeploymentReleaseRecord>();
        var second = new AtomicReference<DeploymentReleaseRecord>();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                first.set(commit(runtime, task, graph("application"), 1));
                return success();
            });
            run(runtime, task -> {
                second.set(commit(runtime, task, changedGraph(), 2));
                return success();
            });
            run(runtime, task -> {
                var repository = releases(runtime);
                assertEquals(2, repository.list(task, "application").size());
                assertThrows(SQLException.class, () -> repository.activateRetained(task, "f".repeat(64), first.get(),
                    publications(graph("application"), 1, List.of()), OBSERVED.plusSeconds(30)));
                verify(runtime, task, changedGraph(), publications(changedGraph(), 2, List.of()));
                repository.activateRetained(task, second.get().identity().sha256(), first.get(),
                    publications(graph("application"), 1, List.of()), OBSERVED.plusSeconds(30));
                var active = repository.find(task, "application", first.get().identity().id()).orElseThrow();
                assertEquals(first.get(), active);
                assertEquals(DeploymentReleaseRecord.DeploymentReleaseState.RETIRED,
                    repository.find(task, "application", second.get().identity().id()).orElseThrow().state());
                verify(runtime, task, graph("application"), publications(graph("application"), 1, List.of()));
                repository.activateRetained(task, second.get().identity().sha256(), first.get(),
                    publications(graph("application"), 1, List.of()), OBSERVED.plusSeconds(31));
                assertEquals(first.get(), repository.find(task, "application", first.get().identity().id()).orElseThrow());
                assertEquals(2, count(runtime, task, "SELECT COUNT(*) FROM deployment_release"));
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void lateHistoryFailureRollsBackCurrentUnitsGraphAndBothReleaseStates() throws Exception {
        enable();
        var first = new AtomicReference<DeploymentReleaseRecord>();
        var second = new AtomicReference<DeploymentReleaseRecord>();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                first.set(commit(runtime, task, graph("application"), 1));
                return success();
            });
            run(runtime, task -> {
                second.set(commit(runtime, task, changedGraph(), 2));
                return success();
            });
            run(runtime, task -> {
                sql(runtime, task, "ALTER TABLE deployment_release ADD CONSTRAINT retained_failure CHECK(release_id<>'"
                    + first.get().identity().id() + "' OR state='RETIRED')");
                assertThrows(SQLException.class, () -> releases(runtime).activateRetained(task,
                    second.get().identity().sha256(), first.get(), publications(graph("application"), 1, List.of()),
                    OBSERVED.plusSeconds(30)));
                verify(runtime, task, changedGraph(), publications(changedGraph(), 2, List.of()));
                assertEquals(second.get(), releases(runtime).find(task, "application", second.get().identity().id()).orElseThrow());
                assertEquals(DeploymentReleaseRecord.DeploymentReleaseState.RETIRED,
                    releases(runtime).find(task, "application", first.get().identity().id()).orElseThrow().state());
                sql(runtime, task, "ALTER TABLE deployment_release DROP CONSTRAINT retained_failure");
                return success();
            });
            valid(runtime);
        }
    }
}
