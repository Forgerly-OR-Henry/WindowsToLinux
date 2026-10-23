package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;

import gold.debug.windowstolinux.appapi.task.action.ActionTarget;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement;
import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainSetup;

/** Tests only channel admission with controlled grants and transport; no host authority is claimed. / 仅以受控授权和传输测试通道准入，不声称宿主授权。 */
class DeploymentBuildCommandChannelTest {

    @Test
    void liveDispatchBoundaryIsNeverObservedForDenialAndPrecedesOriginalExecute() throws Exception {
        var observed = new AtomicInteger();
        var executions = new AtomicInteger();
        var denied = channel(false, executions);
        var server = new ServerId("Server-Exact");
        var targets = Set.of(new ActionTarget(server.id(), "deployment-build", "/fixed"));
        assertEquals(TaskResult.FAILED, denied.send("deployment.build.preflight.v1", targets,
            "'" + gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.PATH + "' build-preflight 'ordinary'",
            new byte[0], java.time.Duration.ofSeconds(30), 65536, observed::incrementAndGet).outcome());
        assertEquals(0, observed.get());
        assertEquals(0, executions.get());
        var allowed = channel(true, executions, request -> {
            assertEquals(1, observed.get());
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN, "CONTROLLED_UNKNOWN", "controlled", Map.of(), Optional.empty())));
        });
        assertEquals(TaskResult.UNKNOWN, allowed.send("deployment.build.preflight.v1", targets,
            "'" + gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.PATH + "' build-preflight 'ordinary'",
            new byte[0], java.time.Duration.ofSeconds(30), 65536, observed::incrementAndGet).outcome());
        assertEquals(1, observed.get());
        assertEquals(1, executions.get());
    }

    /** A refused review never dispatches a helper command. / 被拒绝审核不会发送helper命令。 */
    @Test
    void knownReviewRefusalDoesNotDispatch() throws Exception {
        var calls = new AtomicInteger();
        var channel = channel(false, calls);
        var result = channel.preflight("ordinary");
        assertEquals(TaskResult.FAILED, result.outcome());
        assertEquals(ProblemCode.ACCESS_DENIED, result.problem().orElseThrow().code());
        assertEquals(0, calls.get());
    }

    /** Stable Java build suffix remains a legal exact version at this adapter boundary. / Java稳定构建后缀在适配边界仍是合法精确版本。 */
    @Test
    void stableJavaBuildSuffixCanReachReviewedPreparation() throws Exception {
        var calls = new AtomicInteger();
        var channel = channel(true, calls);
        assertEquals(TaskResult.SUCCEEDED,
            channel.prepare(DeploymentToolchainType.JAVA, "8", "8.0.462-b08", 60, "-").outcome());
        assertEquals(1, calls.get());
    }

    /** The first branch's inadequate patch is known only when that requirement has a second branch. / 首分支补丁不足仅在该需求有第二分支时才属于已知可换候选。 */
    @Test
    void minimumRetryUsesOnlyTheAffectedRequirementAndExactBinding() throws Exception {
        var calls = new AtomicInteger();
        var catalog = DeploymentToolchainCatalog.defaults();
        var channel = channel(true, calls, command -> {
            String script = command.script();
            if (script.contains(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.PATH))
                script = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.verifiedBody(script);
            if (script.contains("prepare-toolchains 'bind'")) {
                String encoded = script.substring(script.indexOf("'bind' '") + 8);
                encoded = encoded.substring(0, encoded.indexOf('\''));
                String binding = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                return reply("TOOLCHAIN_BINDING="
                    + DeploymentToolchainBindingCodec.identity(DeploymentToolchainBindingCodec.decode(binding))
                    + "\n");
            }
            String version = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentToolchainDiagnostics.decode(command.stdin()).arguments().get(2).equals("1.24") ? "1.24.1" : "1.25.1";
            return reply("TOOLCHAIN=GO|" + version + "|/usr/local/lib/windowstolinux/toolchains/versions/go-"
                + "b".repeat(64) + "|MANAGED|https://official.example/go|" + "b".repeat(64) + "\n");
        });
        var preparer = new DeploymentToolchainSetup(channel, catalog);
        var requirement = ToolchainRequirement.declared(DeploymentToolchainType.GO, ">=1.24.9", "go.mod",
            ToolchainRequirement.PurposeType.BUILD);
        assertEquals(2, catalog.candidates(requirement).size());
        var first = preparer.prepare(List.of(requirement), 0, 110);
        assertEquals(TaskResult.FAILED, first.outcome());
        assertEquals("DEPLOYMENT_TOOLCHAIN_MINIMUM", first.problem().orElseThrow().eventCode());
        assertEquals(1, calls.get());
        var second = preparer.prepare(List.of(requirement), 1, 110);
        assertEquals(TaskResult.SUCCEEDED, second.outcome(), second.toString());
        assertEquals("1.25.1", second.value().orElseThrow().selections().getFirst().version().text());
        assertEquals(3, calls.get());
    }

    /** Incomplete output or a binding conflict remains UNKNOWN after one actual dispatch. / 输出不完整或绑定冲突在一次实际发送后保持UNKNOWN。 */
    @Test
    void incompleteBuildAndConflictingBindingNeverReplay() throws Exception {
        var calls = new AtomicInteger();
        var unknown = new OperationResult<CommandResult>(TaskResult.UNKNOWN,
            Optional.of(new CommandResult(Optional.empty(), "partial", "", true)),
            Optional.of(new Problem(ProblemCode.UNKNOWN, "REMOTE_OUTPUT_TRUNCATED", "Incomplete command", Map.of(),
                Optional.empty())));
        var channel = channel(true, calls, command -> unknown);
        var result = channel.build(new DeploymentRemoteWorkspace("demo", "a".repeat(64)),
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().limits(), 60, "ordinary", "echo fixed", "-", List.of(List.of("marker")));
        assertEquals(TaskResult.UNKNOWN, result.outcome());
        assertEquals(1, calls.get());

        var bindingCalls = new AtomicInteger();
        var bindingChannel = channel(true, bindingCalls,
            command -> command.script().contains("'bind'")
                ? reply("TOOLCHAIN_BINDING=" + "0".repeat(64) + "\n")
                : reply("TOOLCHAIN=GO|1.24.1|/usr/local/lib/windowstolinux/toolchains/versions/go-"
                + "b".repeat(64) + "|MANAGED|https://official.example/go|" + "b".repeat(64) + "\n"));
        var requirement = ToolchainRequirement.declared(DeploymentToolchainType.GO, "1.24", "go.mod",
            ToolchainRequirement.PurposeType.BUILD);
        var bound = new DeploymentToolchainSetup(bindingChannel, DeploymentToolchainCatalog.defaults())
            .prepare(List.of(requirement), 0, 110);
        assertEquals(TaskResult.UNKNOWN, bound.outcome());
        assertEquals("DEPLOYMENT_TOOLCHAIN_BINDING_UNCONFIRMED", bound.problem().orElseThrow().eventCode());
        assertEquals(2, bindingCalls.get());
    }

    /** A complete helper unavailable marker is a known unsupported environment, not generic I/O failure. / 完整helper不可用标记是已知环境不支持，而非普通I/O失败。 */
    @Test
    void knownHelperUnavailableKeepsTheOriginalIncompatibleCategory() throws Exception {
        var calls = new AtomicInteger();
        var channel = channel(true, calls,
            command -> new OperationResult<>(TaskResult.FAILED,
                Optional.of(new CommandResult(Optional.of(1),
                    "TOOLCHAIN_FAILURE=unavailable: missing package\n", "", false)),
                Optional.of(new Problem(ProblemCode.IO_FAILURE, "REMOTE_EXIT_NONZERO", "Helper exited",
                    Map.of(), Optional.empty()))));
        var requirement = ToolchainRequirement.declared(DeploymentToolchainType.GO, "1.24", "go.mod",
            ToolchainRequirement.PurposeType.BUILD);
        var prepared = new DeploymentToolchainSetup(channel, DeploymentToolchainCatalog.defaults())
            .prepare(List.of(requirement), 0, 110);
        assertEquals(TaskResult.FAILED, prepared.outcome());
        assertEquals(ProblemCode.INCOMPATIBLE, prepared.problem().orElseThrow().code());
        assertEquals("DEPLOYMENT_TOOLCHAIN_UNSUPPORTED", prepared.problem().orElseThrow().eventCode());
        assertEquals(2, calls.get());
    }

    /** Creates a controlled review and session pair with no real grant validation. / 建立不执行真实授权校验的受控审核与会话。
     * @param approved whether the stub approves / 夹具是否批准
     * @param calls actual execute calls / 实际执行次数
     * @return fixed channel / 固定通道 */
    private static DeploymentBuildCommandChannel channel(boolean approved, AtomicInteger calls) {
        return channel(approved, calls, command -> reply(""));
    }

    /** Uses a caller-owned deterministic response for one fixed command. / 使用调用方确定性响应处理固定命令。
     * @param approved test review decision / 测试审核决定
     * @param calls dispatch count / 发送次数
     * @param response controlled remote reply / 受控远端回执
     * @return fixed channel / 固定通道 */
    private static DeploymentBuildCommandChannel channel(boolean approved, AtomicInteger calls,
                                                         Function<CommandRequest, OperationResult<CommandResult>> response) {
        RemoteSession session = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
            new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                if (method.getName().equals("facts")) return new gold.debug.windowstolinux.appapi.linux.session.RemoteFacts("linux", "amd64", "controlled-fingerprint");
                if (!method.getName().equals("execute"))
                    throw new AssertionError("Unexpected session method");
                calls.incrementAndGet();
                CommandRequest command = (CommandRequest) args[0];
                assertTrue(command.script().contains("prepare-toolchains")
                    || command.script().contains("build-preflight")
                    || gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.verifiedBody(command.script()).startsWith("exec /usr/bin/python3 -I -c "));
                return response.apply(command);
            });
        InteractionService interaction = new InteractionService() {
            public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext task) {
                return approved
                    ? new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new ApprovalGrant(UUID.randomUUID())),
                    Optional.empty())
                    : new OperationResult<>(TaskResult.FAILED, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.ACCESS_DENIED, "REVIEW_DECLINED", "Review declined",
                        Map.of(), Optional.empty())));
            }

            public OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext task) {
                throw new AssertionError("No text requested");
            }
        };
        TaskContext task = (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(),
            new Class<?>[]{TaskContext.class}, (proxy, method, args) -> {
                if (method.getName().equals("id")) return UUID.fromString("11111111-1111-1111-1111-111111111111");
                throw new AssertionError("No task query expected");
            });
        return new DeploymentBuildCommandChannel(session, interaction, new ServerId("Server-Exact"), task,
            "a".repeat(64), new DeploymentBuildBudget(120));
    }

    /** Returns a known complete fixed command reply. / 返回已知完整固定命令回执。
     * @param stdout bounded reply / 有界回执
     * @return successful observation / 成功观测 */
    private static OperationResult<CommandResult> reply(String stdout) {
        return new OperationResult<>(TaskResult.SUCCEEDED,
            Optional.of(new CommandResult(Optional.of(0), stdout, "", false)), Optional.empty());
    }
}
