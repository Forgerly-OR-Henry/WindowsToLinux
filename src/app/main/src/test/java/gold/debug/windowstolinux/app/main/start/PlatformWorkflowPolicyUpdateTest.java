package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformRemoteCapabilitiesTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWorkflowDelegationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.action.CommandReviewMode;
import gold.debug.windowstolinux.appapi.task.action.WorkflowGrant;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real worker scopes and loopback grant consumption across policy revisions. / 真实工作线程作用域与策略修订间的回环许可消费。 */
class PlatformWorkflowPolicyUpdateTest {
    @TempDir
    Path root;

    @Test
    void policySwitchRevokesUnusedGrantsAndRememberedDecisionsWithoutReopeningScope() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                    var interaction = capabilities.interaction();
                    try (var scope = interaction.openReviewedWorkflow(handoff("server"), CommandReviewMode.MANUAL_REVIEW,
                        true, false, task).value().orElseThrow(); var session = capabilities.linux().connect(SERVER, task);
                         var command = command("builtin printf 'policy'")) {
                        var intent = action(command, "policy.test", "/srv/original");
                        var old = interaction.approveReviewedAction(intent, Optional.of(command), false, "Original reviewed action", task)
                            .value().orElseThrow();
                        assertEquals(1, questions(reviews));
                        var automatic = interaction.updateReviewedWorkflowPolicy(scope, 0, CommandReviewMode.AUTOMATIC, task)
                            .value().orElseThrow();
                        assertEquals(CommandReviewMode.AUTOMATIC, automatic.mode());
                        assertEquals(1, automatic.revision());
                        var expired = assertThrows(ApiException.class, () -> session.execute(command, old));
                        assertEquals("WORKFLOW_POLICY_CHANGED", expired.problem().eventCode());
                        assertEquals(0, remote.commands.get());
                        var permitted = interaction.approveReviewedAction(intent, Optional.of(command), false, "Same independently reviewed action", task)
                            .value().orElseThrow();
                        assertEquals(TaskResult.SUCCEEDED, session.execute(command, permitted).outcome());
                        assertEquals(1, questions(reviews));
                        assertEquals(2, interaction.updateReviewedWorkflowPolicy(scope, 1, CommandReviewMode.MANUAL_REVIEW, task)
                            .value().orElseThrow().revision());
                        var manual = interaction.approveReviewedAction(intent, Optional.of(command), false, "Same action after manual policy change", task)
                            .value().orElseThrow();
                        assertEquals(TaskResult.SUCCEEDED, session.execute(command, manual).outcome());
                        assertEquals(2, questions(reviews), "Remembered approval from the earlier policy must not skip the new question");
                        assertEquals(3, interaction.updateReviewedWorkflowPolicy(scope, 2, CommandReviewMode.FULL_CONTROL, task)
                            .value().orElseThrow().revision());
                        assertEquals(1, reviews.approved.stream().filter(i -> i.type().equals("deployment.workflow.policy-update")).count());
                        assertEquals(3, interaction.updateReviewedWorkflowPolicy(scope, 3, CommandReviewMode.FULL_CONTROL, task)
                            .value().orElseThrow().revision(), "An unchanged policy must not add a revision or question");
                        var stale = assertThrows(ApiException.class, () -> interaction.updateReviewedWorkflowPolicy(scope, 2, CommandReviewMode.AUTOMATIC, task));
                        assertEquals("WORKFLOW_POLICY_REVISION", stale.problem().eventCode());
                    }
                    return success(null);
                });
                assertEquals(TaskResult.SUCCEEDED, result(handle).outcome());
                assertEquals(2, remote.commands.get());
            }
        }
    }

    @Test
    void refusalAndForeignOrClosedGrantsNeverReplaceTheOriginalPolicy() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            reviews.policy = intent -> !intent.type().equals("deployment.workflow.policy-update");
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                 var foreign = runtime.capabilities().remoteCapabilities("foreign.mod", VERSION)) {
                var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                    var interaction = capabilities.interaction();
                    var scope = interaction.openReviewedWorkflow(handoff("server"), CommandReviewMode.AUTOMATIC, true, false, task)
                        .value().orElseThrow();
                    try (scope) {
                        var rejected = interaction.updateReviewedWorkflowPolicy(scope, 0, CommandReviewMode.FULL_CONTROL, task);
                        assertEquals(TaskResult.CANCELLED, rejected.outcome());
                        assertTrue(rejected.value().isEmpty());
                        WorkflowGrant forged = () -> {
                        };
                        assertThrows(ApiException.class, () -> interaction.updateReviewedWorkflowPolicy(forged, 0, CommandReviewMode.MANUAL_REVIEW, task));
                        assertThrows(ApiException.class, () -> foreign.interaction().updateReviewedWorkflowPolicy(scope, 0, CommandReviewMode.MANUAL_REVIEW, task));
                        assertEquals(1, interaction.updateReviewedWorkflowPolicy(scope, 0, CommandReviewMode.MANUAL_REVIEW, task)
                            .value().orElseThrow().revision(), "A rejected full-control request must leave revision zero intact");
                    }
                    assertThrows(ApiException.class, () -> interaction.updateReviewedWorkflowPolicy(scope, 1, CommandReviewMode.AUTOMATIC, task));
                    return success(null);
                });
                assertEquals(TaskResult.SUCCEEDED, result(handle).outcome());
                assertEquals(0, remote.commands.get());
            }
        }
    }
}
