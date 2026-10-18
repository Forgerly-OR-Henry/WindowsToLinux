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

/** Reviews fixed commands through the real provider without any SSH dispatch. / 真实模型审查固定命令，不连接或发送SSH。 */
@EnabledIfSystemProperty(named = "managed.runtime.staticLive", matches = "true")
class DeploymentAssistedCapacityReviewLiveTest extends DeploymentStaticLiveAcceptanceTest {
    @Test
    void reviewsCapacityWithoutRemoteDispatch() throws Exception {
        reviewWithoutRemoteDispatch("capacity");
    }

    @Test
    void reviewsOriginalBindingWithoutRemoteDispatch() throws Exception {
        reviewWithoutRemoteDispatch("binding");
    }

    @Test
    void reviewsJavaPreparationWithoutRemoteDispatch() throws Exception {
        reviewWithoutRemoteDispatch("preparation");
    }

    @Test
    void reviewsHostProbeWithoutRemoteDispatch() throws Exception {
        reviewWithoutRemoteDispatch("host-probe");
    }

    @Test
    void reviewsRustPreparationWithTheProductDeploymentGoalWithoutRemoteDispatch() throws Exception {
        reviewWithoutRemoteDispatch("rust-preparation");
    }

    @Test
    void reviewsEmptyConfigurationWithoutRemoteDispatch() throws Exception {
        reviewWithoutRemoteDispatch("configuration");
    }

    private void reviewWithoutRemoteDispatch(String kind) throws Exception {
        boolean binding = kind.equals("binding");
        boolean rust = kind.equals("rust-preparation");
        boolean preparation = kind.equals("preparation") || rust;
        boolean hostProbe = kind.equals("host-probe");
        boolean config = kind.equals("configuration");
        var configuration = gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot.create(
            "log-analyzer-api-review", 1, "deployment-1", java.time.Instant.EPOCH, List.of());
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        var enabled = DeploymentStaticLiveAcceptanceTest.class.getDeclaredField("aiEnabled");
        enabled.setAccessible(true);
        enabled.setBoolean(this, true);
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
                    config ? deploymentGoal(server, "Draft[application=" + configuration.applicationId() + ", normal configuration entries=0, configuration identity=" + configuration.sha256()
                        + "]; review ordinary empty configuration staging, not installation or runtime execution; API-only, no SSH dispatch")
                        : rust ? rustDeploymentGoal(server)
                        : hostProbe ? "Review the exact host capability observation for the user's authorized task-board deployment on the selected server; no SSH dispatch"
                        : preparation ? "Review the guarded Java 21 catalog prerequisite for the user's authorized task-board deployment, including permitted official download/install and SYSTEM reuse effects; no SSH dispatch"
                        : binding ? "Review the original task-board-12 binding bytes (matched to original SHA-256); no claim of current installation, no SSH dispatch"
                        : "Review a read-only filesystem capacity prerequisite on the selected server; no SSH dispatch");
                     var request = new CommandRequest(config
                         ? gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.verifiedCommand(
                         "'" + layout.helperPath() + "' 'stage-config' '" + configuration.applicationId() + "' '" + configuration.sha256()
                             + "' 'systemd' 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' '0'")
                         : hostProbe
                         ? gold.debug.windowstolinux.mod.deployment.linux.probe.DeploymentPlatformCapabilityProbe.render()
                         : preparation
                         ? gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.verifiedCommand(
                         "'" + gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.PATH
                             + (rust ? "' prepare-toolchains 'prepare' 'RUST' '1.95' '-' '1777' '-'"
                             : "' prepare-toolchains 'prepare' 'JAVA' '21' '-' '300' '-'"))
                         : binding ? originalBindingCommand()
                         : "set -euo pipefail\ntest -d '/var/lib' && test ! -L '/var/lib'\nstat -f -c '%a %S' -- '/var/lib'\n",
                         new byte[0], Duration.ofSeconds(config ? 90 : hostProbe ? 120 : rust ? 1792 : preparation ? 315 : 20), preparation || hostProbe ? 65536 : 1024)) {
                    var intent = RemoteActions.command(server, revision, request).withSemantics(new ActionSemantics(
                        config ? "deployment.publication.config.v1" : hostProbe ? "deployment.candidate.host-probe.v1" : preparation ? "deployment.toolchain.prepare.v1" : binding ? "deployment.toolchain.bind.v1" : "deployment.candidate.capacity.v1",
                        config ? Set.of(new ActionTarget(server.id(), "deployment-configuration", layout.configurationRoot(configuration.applicationId()) + "/" + configuration.sha256() + "/systemd.env"),
                            new ActionTarget(server.id(), "deployment-release", layout.applicationRoot(configuration.applicationId())))
                            : Set.of(new ActionTarget(server.id(), hostProbe ? "deployment-host" : binding || preparation ? "deployment-build" : "deployment-workspace",
                            hostProbe ? server.id() : binding || preparation ? layout.toolchains() : "/var/lib"))));
                    var result = DeploymentFixedCommandApproval.approveWithEvidence(context, context.interaction(), intent, request,
                        task, revision, "AUTOMATIC", true, (actual, command, supplied) -> java.util.Map.of(
                            "workflowAuthorization", DeploymentWorkflowAuthorization.reviewFacts(authority, supplied, actual)));
                    return new OperationResult<Void>(result.outcome(), Optional.empty(), result.problem());
                } catch (Exception failure) {
                    // No SSH capability is invoked by this fixture; a failed review is a known failed test, never a deployment effect.
                    gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.operationFailure(context, task, failure);
                    var problem = failure instanceof gold.debug.windowstolinux.appapi.task.result.ApiException api ? api.problem()
                        : new gold.debug.windowstolinux.appapi.task.result.Problem(
                        gold.debug.windowstolinux.appapi.task.result.ProblemCode.INVALID_INPUT, "ASSISTED_REVIEW_FIXTURE_FAILURE",
                        "The API-only review failed without SSH dispatch", java.util.Map.of("causeType", failure.getClass().getSimpleName()), Optional.empty());
                    return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(problem));
                }
            });
            System.out.println("ASSISTED_API_REVIEW_TASK kind=" + kind + " task=" + handle.id() + " state=" + root);
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

    private static String rustDeploymentGoal(ServerId server) throws Exception {
        return deploymentGoal(server,
            "Draft[application=log-analyzer-api-review, selectedSource=success-log-analyzer, cli/Cargo.toml rust-version>=1.85, selected catalog branch=1.95]; API-only review, no SSH dispatch");
    }

    private static String deploymentGoal(ServerId server, String request) throws Exception {
        var formatter = gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.policy.DeploymentPublishPolicyContext.class
            .getDeclaredMethod("workflowRequest", ServerId.class, String.class);
        formatter.setAccessible(true);
        return (String) formatter.invoke(null, server, request);
    }

    @Test
    void inspectsApiOnlyReviewWithoutDispatch() throws Exception {
        root = java.nio.file.Path.of(System.getProperty("managed.live.originalState")).toRealPath();
        assertTrue(root.startsWith(workspace().resolve("src/app/db/data").toRealPath()));
        assertTrue(root.getFileName().toString().startsWith("ai-live-preparation-review-"));
        var original = java.util.UUID.fromString(System.getProperty("managed.live.originalTask"));
        var protection = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("currentUserProtection");
        protection.setAccessible(true);
        try (var database = gold.debug.windowstolinux.app.db.connect.PlatformDatabase.open(root.resolve("data"),
            (gold.debug.windowstolinux.app.db.init.DatabaseProtection) protection.invoke(null))) {
            System.out.println("ASSISTED_API_REVIEW_ORIGINAL " + new gold.debug.windowstolinux.app.db.task.TaskLedger(database).queries().snapshot(original));
            var printCause = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("printOriginalCallCause", String.class);
            printCause.setAccessible(true);
            var logs = new gold.debug.windowstolinux.app.db.log.LogStore(database);
            for (var target : logs.targets())
                for (var event : logs.read(target)) {
                    if (original.equals(event.taskId())) {
                        System.out.println("ASSISTED_API_REVIEW_ORIGINAL_EVENT " + event.eventCode() + " " + event.message());
                        if (event.eventCode().equals("DEPLOYMENT_AI_USAGE"))
                            System.out.println("ASSISTED_API_REVIEW_USAGE details=Optional[" + event.details() + "]");
                    }
                    if (event.details() != null && event.details().contains("context.taskId=" + original))
                        printCause.invoke(null, event.details());
                }
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
