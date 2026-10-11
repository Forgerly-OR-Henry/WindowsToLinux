package gold.debug.windowstolinux.mod.deployment.db.release;

import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.ref.ResourceRefResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionFixture;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionIdentity;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionIdentity.DeploymentExecutionMode;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationReader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentReleaseOriginModesTest extends DeploymentExecutionFixture {
    private DeploymentExecutionMode mode;

    @Override
    public DeploymentExecutionIdentity identity(TaskContext task, UUID id) {
        var original = super.identity(task, id);
        return new DeploymentExecutionIdentity(original.executionId(), original.taskId(), original.projectId(),
            original.applicationId(), original.server(), mode, original.reviewedRevision());
    }

    @ParameterizedTest
    @EnumSource(DeploymentExecutionMode.class)
    void eachModeKeepsDraftsOutOfFormalAppsAndRetainsItsVerifiedCreatingIdentity(DeploymentExecutionMode selected) throws Exception {
        mode = selected;
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var execution = UUID.randomUUID();
                var reference = prepare(runtime, task, execution);
                try (var connection = context.db().openConnection(task);
                     var query = connection.createStatement();
                     var rows = query.executeQuery("SELECT (SELECT COUNT(*) FROM managed_application), (SELECT COUNT(*) FROM deployment_release)")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                    assertEquals(0, rows.getInt(2));
                }
                var graph = graph("application");
                var records = publications(graph, 1, java.util.List.of());
                var store = journal(runtime);
                store.commitApplication(task, execution, graph, records, OBSERVED.plusSeconds(3));
                context.servers().resolve(reference.id(), ResourceRefResult.REFERENCED, task);
                store.finishCleanup(task, execution, OBSERVED.plusSeconds(4));
                var release = new DeploymentReleaseStore(context.db()).list(task, "application").getFirst();
                try (var connection = context.db().openConnection(task)) {
                    assertEquals(identity(task, execution), DeploymentReleaseOrigin.read(connection, release));
                }
                assertTrue(store.find(task, execution).isPresent(), "Shared persistence does not run Agent cleanup");
                assertTrue(new DeploymentApplicationReader(context.db()).findForCompletedTask(task.id(), task).isPresent());
                verify(runtime, task, graph, records);
                return success();
            });
            valid(runtime);
        }
    }
}
