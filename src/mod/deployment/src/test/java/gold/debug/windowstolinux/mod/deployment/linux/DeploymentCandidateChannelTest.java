package gold.debug.windowstolinux.mod.deployment.linux;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.ResourceLease;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;
import org.junit.jupiter.api.Test;

/** Checks fixed candidate command parsing without claiming a real SSH session. / 验证固定候选命令解析，不声称真实SSH会话。 */
class DeploymentCandidateChannelTest {
    /** Refuses an overflowing host capacity observation. / 拒绝溢出的主机容量观测。 */
    @Test
    void capacityRequiresExistingParentAndBoundedNumber() throws Exception {
        var script = new ArrayList<String>();
        var channel = channel((command) -> {
            script.add(command.script());
            return reply(0, "99999999999999999999 4096\n");
        });
        var result = channel.availableBytes();
        assertEquals(TaskResult.FAILED, result.outcome());
        assertEquals("DEPLOYMENT_CAPACITY_INVALID", result.problem().orElseThrow().eventCode());
        assertEquals(1, script.size());
        assertTrue(script.getFirst().contains("test -d '/var/lib' && test ! -L '/var/lib'"));
        assertTrue(script.getFirst().contains("stat -f -c '%a %S' -- '/var/lib'"));
        assertFalse(script.getFirst().contains("stat -f -c '%a %S' -- '/var/lib/windowstolinux'"));
    }

    /** Refuses a successful transport envelope without a capacity payload. / 拒绝缺容量载荷的成功传输封套。 */
    @Test
    void missingSuccessfulCapacityPayloadDoesNotBecomeSuccess() throws Exception {
        var channel = channel(command -> new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty()));
        var result = channel.availableBytes();
        assertEquals(TaskResult.FAILED, result.outcome());
        assertEquals("DEPLOYMENT_CAPACITY_MISSING", result.problem().orElseThrow().eventCode());
    }

    /** An early CANDIDATE line cannot attest the subsequent task marker write. / 先输出的CANDIDATE行不能证明随后任务标记已写入。 */
    @Test
    void candidatePathWithNonzeroExitNeverQueriesOrUploads() throws Exception {
        var calls = new AtomicInteger();
        var workspace = new DeploymentRemoteWorkspace("demo", "a".repeat(64));
        var channel = channel(command -> {
            calls.incrementAndGet();
            assertTrue(command.script().contains("candidate-create-task"));
            return reply(1, "CANDIDATE=" + workspace.candidateRoot() + "\n");
        });
        var result = channel.create(workspace, 64L * 1024 * 1024);
        assertEquals(TaskResult.UNKNOWN, result.outcome());
        assertEquals("DEPLOYMENT_CANDIDATE_CREATE_UNCONFIRMED", result.problem().orElseThrow().eventCode());
        assertEquals(1, calls.get());
    }

    /** Builds one non-authoritative controlled session for command boundary tests. / 为命令边界测试构造一份非权威受控会话。
     * @param responder fixed command responder / 固定命令回执方
     * @return original-task candidate channel / 原任务候选通道 */
    private static DeploymentCandidateChannel channel(
        java.util.function.Function<CommandRequest, OperationResult<CommandResult>> responder) {
        var task = new TaskContext() {
            public UUID id() {
                return UUID.fromString("11111111-1111-4111-8111-111111111111");
            }

            public CancelToken cancelToken() {
                return () -> false;
            }

            public void progress(String text, Optional<Double> fraction) {
                throw new AssertionError();
            }

            public ResourceLease retain(String kind, String localId, String description) {
                throw new AssertionError();
            }
        };
        var interaction = new InteractionService() {
            public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext actual) {
                assertSame(task, actual);
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new ApprovalGrant(UUID.randomUUID())),
                    Optional.empty());
            }

            public OperationResult<String> requestText(String title, String prompt, boolean secret,
                                                       TaskContext actual) {
                throw new AssertionError();
            }
        };
        RemoteSession session = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
            new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                if (method.getName().equals("execute"))
                    return responder.apply((CommandRequest) args[0]);
                throw new AssertionError(method.getName());
            });
        return new DeploymentCandidateChannel(session, interaction, new ServerId("server"), task, "f".repeat(64),
            new DeploymentBuildBudget(3600));
    }

    /** Returns a fixed observed process exit. / 返回固定已观测进程退出。
     * @param exit process exit / 进程退出
     * @param stdout output text / 输出文本
     * @return complete host observation / 完整宿主观测 */
    private static OperationResult<CommandResult> reply(int exit, String stdout) {
        return new OperationResult<>(exit == 0 ? TaskResult.SUCCEEDED : TaskResult.FAILED,
            Optional.of(new CommandResult(Optional.of(exit), stdout, "", false)),
            exit == 0
                ? Optional.empty()
                : Optional.of(new Problem(ProblemCode.IO_FAILURE, "FIXTURE_EXIT", "Controlled command failed",
                Map.of(), Optional.empty())));
    }
}
