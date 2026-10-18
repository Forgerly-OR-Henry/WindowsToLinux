package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandApproval;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/** Reviews the actual capacity command through the real provider without any SSH dispatch. / 真实模型审查容量命令，不连接或发送SSH。 */
@EnabledIfSystemProperty(named = "managed.runtime.staticLive", matches = "true")
class DeploymentAssistedCapacityReviewLiveTest extends DeploymentStaticLiveAcceptanceTest {
    @Test
    void reviewsCapacityWithoutRemoteDispatch() throws Exception {
        reviewWithoutRemoteDispatch(false);
    }

    @Test
    void reviewsOriginalBindingWithoutRemoteDispatch() throws Exception {
        reviewWithoutRemoteDispatch(true);
    }

    private void reviewWithoutRemoteDispatch(boolean binding) throws Exception {
        var enabled = DeploymentStaticLiveAcceptanceTest.class.getDeclaredField("aiEnabled");
        enabled.setAccessible(true);
        enabled.setBoolean(this, true);
        String kind = binding ? "binding" : "capacity";
        root = workspace().resolve("src/app/db/data/ai-live-" + kind + "-review-" + Long.toUnsignedString(System.nanoTime(), 36));
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            for (String method : List.of("saveServer", "configureLiveModel")) {
                var configure = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod(method, PlatformRuntime.class);
                configure.setAccessible(true);
                configure.invoke(this, runtime);
            }
            var context = runtime.modContexts().get(OWNER).context();
            var handle = context.tasks().submit(new TaskRequest("ai-live-" + kind + "-review",
                "Review the exact " + kind + " command without connecting or executing it", List.of("static-live"), true), task -> {
                String revision = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
                var server = new ServerId("static-live");
                try (var authority = DeploymentWorkflowAuthorization.open(context, task, server, revision, "AUTOMATIC", true,
                    binding ? "Review the original task-board-12 binding bytes (matched to original SHA-256); no claim of current installation, no SSH dispatch"
                        : "Review a read-only filesystem capacity prerequisite on the selected server; no SSH dispatch");
                     var request = new CommandRequest(binding ? originalBindingCommand()
                         : "set -euo pipefail\ntest -d '/var/lib' && test ! -L '/var/lib'\nstat -f -c '%a %S' -- '/var/lib'\n",
                         new byte[0], Duration.ofSeconds(20), 1024)) {
                    var intent = RemoteActions.command(server, revision, request).withSemantics(new ActionSemantics(
                        binding ? "deployment.toolchain.bind.v1" : "deployment.candidate.capacity.v1",
                        Set.of(new ActionTarget(server.id(), binding ? "deployment-build" : "deployment-workspace",
                            binding ? gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original().toolchains() : "/var/lib"))));
                    var result = DeploymentFixedCommandApproval.approve(context, context.interaction(), intent, request,
                        task, revision, "AUTOMATIC", true);
                    return new OperationResult<Void>(result.outcome(), Optional.empty(), result.problem());
                }
            });
            var result = handle.completion().toCompletableFuture().get(5, TimeUnit.MINUTES);
            for (var category : runtime.logs().inventory(false).categories())
                for (var record : runtime.logs().inspect(category.target().key()).records())
                    if (record.event().taskId().filter(handle.id()::equals).isPresent()
                        && Set.of("DEPLOYMENT_AI_USAGE", "DEPLOYMENT_COMMAND_REVIEW_DENIED").contains(record.event().eventCode()))
                        System.out.println("ASSISTED_CAPACITY_REVIEW_DIAGNOSTIC " + record.event());
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result::toString);
            System.out.println("ASSISTED_CAPACITY_REVIEW_PASS kind=" + kind + " task=" + handle.id() + " sshDispatches=0 state=" + root);
        }
    }

    private static String originalBindingCommand() {
        String payload = "WTL-TOOLS-1\t2026-09-09.1\nJAVA\tLANGUAGE_TARGET\tMjE=\tcG9tLnhtbA==\tSERIES\t21\t21.0.12.1+1\t"
            + "/usr/local/lib/windowstolinux/toolchains/versions/java-0e6db83ffe5fbb34adf155ce772477836ca6b5db1fd84531b1d200ef575fdd84\tSYSTEM\t"
            + java.util.Base64.getEncoder().encodeToString("system:/usr/lib/jvm/java-21-openjdk-amd64/bin/java".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            + "\t244c33fad7a87b0b26578180fd95a1fa8bf8008540221712258a4f778502bb8c\n";
        var decoded = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec.decode(payload);
        assertEquals("bdfc64144fd5e5ae319a2036967c97f37c001b1c49623010cb88e249f7a920d7",
            gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec.identity(decoded));
        return gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.verifiedCommand(
            "'" + gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.PATH + "' prepare-toolchains 'bind' '"
                + java.util.Base64.getEncoder().encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "'");
    }
}
