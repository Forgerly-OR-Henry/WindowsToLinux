package gold.debug.windowstolinux.mod.deployment.linux;

import gold.debug.windowstolinux.appapi.task.ActionIntent;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.InteractionService;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.ResourceLease;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.CancelToken;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.junit.jupiter.api.Test;

/** Exercises fixed publication receipts with a controlled remote peer. / 用受控远端对等端验证固定发布回执。 */
class DeploymentPublicationChannelTest {
    /** A distinct attempt preserves real task approval and exact five/eight/four literal arguments. / 独立尝试保留真实任务审批及精确五／八／四个字面参数。 */
    @Test
    void distinctAttemptKeepsRootTaskAndOperationInEveryEvidenceCommand() throws Exception {
        var operation = UUID.fromString("22222222-2222-4222-8222-222222222222");
        var scripts = new ArrayList<String>();
        var peer = channel(request -> {
            if (request.script().contains("snapshot-deployment-attempt"))
                return reply(0, "SNAPSHOT_TOKEN=" + operation + "\nPREVIOUS=1\nPREVIOUS_RUNNING=1\nPREVIOUS_DIGEST="
                    + "d".repeat(64) + "\nPREVIOUS_KIND=deployment\n");
            if (request.script().contains("publication-original-status-attempt"))
                return reply(0, "PUBLICATION_STATUS=1\nCURRENT=" + "d".repeat(64)
                    + "\nCURRENT_KIND=deployment\nCURRENT_MODE=DAEMON\nSNAPSHOT=" + operation + "\nSNAPSHOT_OLD="
                    + "d".repeat(64) + "\nSNAPSHOT_KIND=deployment\nSNAPSHOT_RUNNING=1\nMAINTENANCE=none\n"
                    + "CANDIDATE=owned\nBUILD_ACTIVE=no\nUNIT_PRESENT=yes\nCONTAINER_PRESENT=not-applicable\nRETAINED_COUNT=1\n");
            return reply(0, "SNAPSHOT_DISCARDED=1\n");
        }, scripts);
        assertEquals(Optional.of(operation.toString()), peer.snapshot(unit(), runtime(), operation,
            Optional.of("d".repeat(64))).value().orElseThrow().token());
        assertEquals(Outcome.SUCCEEDED, peer.originalStatus(unit(), "demo-" + "a".repeat(16),
            Optional.of("d".repeat(64)), "native", "none", operation).outcome());
        assertEquals(Outcome.SUCCEEDED, peer.discardSnapshot(unit(), operation).outcome());
        assertEquals(3, scripts.size());
        String identity = "'demo' '" + "b".repeat(64) + "' '11111111-1111-4111-8111-111111111111' '" + operation + "'";
        assertTrue(scripts.get(0).contains("'snapshot-deployment-attempt' " + identity + " '" + "d".repeat(64) + "'"), scripts.get(0));
        assertTrue(scripts.get(1).contains("'publication-original-status-attempt' " + identity + " 'demo-" + "a".repeat(16)
            + "' '" + "d".repeat(64) + "' 'native' 'none'"), scripts.get(1));
        assertTrue(scripts.get(2).contains("'snapshot-discard-attempt' " + identity), scripts.get(2));
    }

    /** A receipt cannot substitute root token, stale CURRENT or first-deploy evidence for reviewed previous state. / 回执不能以根令牌、漂移当前或首次发布事实替换已审阅旧状态。 */
    @Test
    void attemptSnapshotRejectsBorrowedTokenAndChangedPreviousWithoutAnotherSend() throws Exception {
        var operation = UUID.fromString("22222222-2222-4222-8222-222222222222");
        for (String body : List.of("PREVIOUS=0\n",
            "SNAPSHOT_TOKEN=11111111-1111-4111-8111-111111111111\nPREVIOUS=1\nPREVIOUS_RUNNING=1\nPREVIOUS_DIGEST="
                + "d".repeat(64) + "\nPREVIOUS_KIND=deployment\n",
            "SNAPSHOT_TOKEN=" + operation + "\nPREVIOUS=1\nPREVIOUS_RUNNING=1\nPREVIOUS_DIGEST="
                + "e".repeat(64) + "\nPREVIOUS_KIND=deployment\n")) {
            var scripts = new ArrayList<String>();
            assertEquals(Outcome.UNKNOWN, channel(request -> reply(0, body), scripts)
                .snapshot(unit(), runtime(), operation, Optional.of("d".repeat(64))).outcome());
            assertEquals(1, scripts.size());
        }
        var scripts = new ArrayList<String>();
        assertEquals(Outcome.SUCCEEDED, channel(request -> reply(0, "PREVIOUS=0\n"), scripts)
            .snapshot(unit(), runtime(), operation, Optional.empty()).outcome());
        assertTrue(scripts.getFirst().endsWith(" 'empty'"), scripts.getFirst());
    }

    /** A correct digest/token cannot hide changed previous runtime kind. / 正确摘要和令牌不能掩盖旧运行类别变化。
     * @throws Exception if admission fails / 准入失败时 */
    @Test
    void attemptSnapshotRejectsChangedPreviousKindWithoutReplayingSnapshot() throws Exception {
        var operation = UUID.fromString("22222222-2222-4222-8222-222222222222");
        var scripts = new ArrayList<String>();
        var result = channel(request -> reply(0, "SNAPSHOT_TOKEN=" + operation
            + "\nPREVIOUS=1\nPREVIOUS_RUNNING=1\nPREVIOUS_DIGEST=" + "d".repeat(64)
            + "\nPREVIOUS_KIND=ordinary\n"), scripts)
            .snapshot(unit(), runtime(), operation, Optional.of("d".repeat(64)), "deployment", true);
        assertEquals(Outcome.UNKNOWN, result.outcome());
        assertEquals("DEPLOYMENT_SNAPSHOT_PREVIOUS_CHANGED", result.problem().orElseThrow().eventCode());
        assertEquals(1, scripts.size());
        assertTrue(scripts.getFirst().contains("'" + operation + "'"));
    }

    /** A correct digest/token and kind cannot hide changed previous activity. / 正确摘要、令牌和类别不能掩盖旧运行状态变化。
     * @throws Exception if admission fails / 准入失败时 */
    @Test
    void attemptSnapshotRejectsChangedPreviousActivityWithoutReplayingSnapshot() throws Exception {
        var operation = UUID.fromString("22222222-2222-4222-8222-222222222222");
        var scripts = new ArrayList<String>();
        var result = channel(request -> reply(0, "SNAPSHOT_TOKEN=" + operation
            + "\nPREVIOUS=1\nPREVIOUS_RUNNING=0\nPREVIOUS_DIGEST=" + "d".repeat(64)
            + "\nPREVIOUS_KIND=deployment\n"), scripts)
            .snapshot(unit(), runtime(), operation, Optional.of("d".repeat(64)), "deployment", true);
        assertEquals(Outcome.UNKNOWN, result.outcome());
        assertEquals("DEPLOYMENT_SNAPSHOT_PREVIOUS_CHANGED", result.problem().orElseThrow().eventCode());
        assertEquals(1, scripts.size());
        assertTrue(scripts.getFirst().contains("'" + operation + "'"));
    }

    /** Empty previous state requires none/inactive before sending any snapshot. / 空旧状态在发送快照前必须是none和非运行。
     * @throws Exception if admission fails / 准入失败时 */
    @Test
    void emptyAttemptSnapshotRequiresCompleteNoneAndInactiveBeforeDispatch() throws Exception {
        var operation = UUID.fromString("22222222-2222-4222-8222-222222222222");
        var scripts = new ArrayList<String>();
        var peer = channel(request -> reply(0, "PREVIOUS=0\n"), scripts);
        assertThrows(IllegalArgumentException.class,
            () -> peer.snapshot(unit(), runtime(), operation, Optional.empty(), "deployment", false));
        assertThrows(IllegalArgumentException.class,
            () -> peer.snapshot(unit(), runtime(), operation, Optional.empty(), "none", true));
        assertTrue(scripts.isEmpty());
        var result = peer.snapshot(unit(), runtime(), operation, Optional.empty(), "none", false);
        assertEquals(Outcome.SUCCEEDED, result.outcome());
        assertEquals("none", result.value().orElseThrow().previousKind());
        assertFalse(result.value().orElseThrow().previousRunning());
        assertTrue(result.value().orElseThrow().token().isEmpty());
        assertEquals(1, scripts.size());
        assertTrue(scripts.getFirst().contains("'" + operation + "'"));
    }

    /** Rejects a missing first-release flag and preserves an exact previous token. / 拒绝缺失首次发布标志并保留精确旧令牌。 */
    @Test
    void snapshotRequiresCompleteExplicitPreviousEvidence() throws Exception {
        var unit = unit();
        var missing = channel(request -> reply(0, ""), new ArrayList<>());
        assertEquals(Outcome.UNKNOWN, missing.snapshot(unit, runtime()).outcome());
        var previous = channel(request -> reply(0,
                "SNAPSHOT_TOKEN=11111111-1111-4111-8111-111111111111\nPREVIOUS=1\nPREVIOUS_RUNNING=1\nPREVIOUS_DIGEST="
                    + "d".repeat(64) + "\nPREVIOUS_KIND=deployment\n"),
            new ArrayList<>());
        assertTrue(previous.snapshot(unit, runtime()).value().orElseThrow().previousRunning());
        var foreign = channel(request -> reply(0,
                "SNAPSHOT_TOKEN=22222222-2222-4222-8222-222222222222\nPREVIOUS=1\nPREVIOUS_RUNNING=1\nPREVIOUS_DIGEST="
                    + "d".repeat(64) + "\nPREVIOUS_KIND=deployment\n"),
            new ArrayList<>());
        assertEquals(Outcome.UNKNOWN, foreign.snapshot(unit, runtime()).outcome());
    }

    /** Task-bound status accepts only complete facts and never converts malformed activity to false. / 原任务状态仅接收完整事实，绝不把畸形活动值转为否。 */
    @Test
    void originalStatusRejectsMissingAndConflictingRecoveryFacts() throws Exception {
        var unit = unit();
        String body = "PUBLICATION_STATUS=1\nCURRENT=" + "d".repeat(64)
            + "\nCURRENT_KIND=deployment\nCURRENT_MODE=DAEMON\nSNAPSHOT=11111111-1111-4111-8111-111111111111\nSNAPSHOT_OLD="
            + "e".repeat(64) + "\nSNAPSHOT_KIND=deployment\nSNAPSHOT_RUNNING=1\nMAINTENANCE=none\n"
            + "CANDIDATE=owned\nBUILD_ACTIVE=no\nUNIT_PRESENT=yes\nCONTAINER_PRESENT=not-applicable\nRETAINED_COUNT=1\n";
        var good = channel(request -> reply(0, body), new ArrayList<>());
        var observed = good.originalStatus(unit, "demo-" + "a".repeat(16), Optional.of("d".repeat(64)), "native",
            "none");
        assertEquals(Outcome.SUCCEEDED, observed.outcome());
        assertEquals(Optional.of("e".repeat(64)), observed.value().orElseThrow().previousDigest());
        for (String malformed : List.of(body.replace("SNAPSHOT_RUNNING=1", "SNAPSHOT_RUNNING=wrong"),
            body.replace("SNAPSHOT=11111111-1111-4111-8111-111111111111",
                "SNAPSHOT=22222222-2222-4222-8222-222222222222"),
            body.replace("BUILD_ACTIVE=no", "BUILD_ACTIVE=maybe"),
            body.replace("CONTAINER_PRESENT=not-applicable", "CONTAINER_PRESENT=no"), body + "CURRENT=none\n")) {
            var peer = channel(request -> reply(0, malformed), new ArrayList<>());
            assertEquals(Outcome.UNKNOWN,
                peer.originalStatus(unit, "demo-" + "a".repeat(16), Optional.of("d".repeat(64)), "native", "none")
                    .outcome());
        }
    }

    /** A full known publish failure can recover; legal stop diagnostics precede its unique final marker. / 完整已知发布失败可恢复；合法停机诊断位于唯一末尾标记前。 */
    @Test
    void publicationDistinguishesKnownFailureFromVerifiedOutput() throws Exception {
        var unit = unit();
        var args = new PublishArguments(unit);
        var failed = channel(request -> reply(1, "STOP_BEFORE=1\n"), new ArrayList<>());
        assertEquals(Outcome.FAILED, args.publish(failed).outcome());
        var success = channel(request -> reply(0, "STOP_BEFORE=1\nSTOP_AFTER=1\nPUBLISHED=1\n"), new ArrayList<>());
        assertEquals(Outcome.SUCCEEDED, args.publish(success).outcome());
        var forged = channel(request -> reply(0, "notPUBLISHED=1\n"), new ArrayList<>());
        assertEquals(Outcome.UNKNOWN, args.publish(forged).outcome());
    }

    /** Only the original task's exact candidate cleanup is sent after known business resolution. / 只发送原任务精确候选清理。 */
    @Test
    void candidateCleanupKeepsTaskBindingAndExactReceipt() throws Exception {
        var requests = new ArrayList<String>();
        var unit = unit();
        var channel = channel(request -> reply(0, "CANDIDATE_CLEANED=1\n"), requests);
        assertEquals(Outcome.SUCCEEDED, channel.cleanupCandidate(unit, "demo-" + "a".repeat(16)).outcome());
        assertEquals(1, requests.size());
        assertTrue(requests.getFirst().contains("candidate-cleanup-task"), requests.getFirst());
        assertTrue(requests.getFirst().contains("demo-" + "a".repeat(16)), requests.getFirst());
        assertTrue(requests.getFirst().contains("11111111-1111-4111-8111-111111111111"), requests.getFirst());
        assertEquals(Outcome.UNKNOWN, channel(unitRequest -> reply(0, "CANDIDATE=other\n"), new ArrayList<>())
            .cleanupCandidate(unit, "demo-" + "a".repeat(16)).outcome());
    }

    /** Unconfirmed publish and cleanup receipts never become known successful effects. / 未确认的发布和清理回执绝不成为已知成功副作用。 */
    @Test
    void ambiguousEffectsStayUnknownWithoutInventingCompletion() throws Exception {
        var unit = unit();
        var requests = new ArrayList<String>();
        var channel = channel(request -> new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "FIXTURE_RECEIPT_LOST", "Controlled loss", Map.of(),
                    Optional.empty()))),
            requests);
        assertEquals(Outcome.UNKNOWN, new PublishArguments(unit).publish(channel).outcome());
        assertEquals(Outcome.UNKNOWN, channel.cleanupCandidate(unit, "demo-" + "a".repeat(16)).outcome());
        assertEquals(2, requests.size());
        assertFalse(requests.stream().anyMatch(script -> script.contains("rollback")));
    }

    /** A cancellation returned after execute was called does not prove the remote command stayed unsent. / execute调用后的取消不证明远端命令未发送。 */
    @Test
    void executeCancellationCannotBeMistakenForAdmissionRefusal() throws Exception {
        var requests = new ArrayList<String>();
        var peer = channel(request -> new OperationResult<>(Outcome.CANCELLED, Optional.empty(),
                Optional.of(new Problem(ProblemCode.CANCELLED, "FIXTURE_CANCELLED", "Controlled cancellation", Map.of(),
                    Optional.empty()))),
            requests);
        assertEquals(Outcome.UNKNOWN, peer.cleanupCandidate(unit(), "demo-" + "a".repeat(16)).outcome());
        assertEquals(1, requests.size());
    }

    /** The channel releases its request copy after original secret staging while leaving the borrowed value to its caller. / 原秘密暂存后通道清零请求副本，而暂借值仍归调用方。 */
    @Test
    void secretStagingReleasesOnlyItsOwnedCommandInput() throws Exception {
        var captured = new java.util.concurrent.atomic.AtomicReference<CommandRequest>();
        byte[] borrowed = {11, 12, 13};
        var peer = channel(request -> {
            captured.set(request);
            assertArrayEquals(borrowed, request.stdin());
            return reply(0, "INPUT_STAGED=1\n");
        }, new ArrayList<>());
        assertEquals(Outcome.SUCCEEDED, peer.stageSecret(unit(), "db-secret", 2, borrowed).outcome());
        assertArrayEquals(new byte[]{11, 12, 13}, borrowed);
        assertArrayEquals(new byte[3], captured.get().stdin());
        java.util.Arrays.fill(borrowed, (byte) 0);
    }

    /** On-demand container needs only its helper identity; malformed native numbers remain unresolved. / 按需容器只需helper身份；畸形原生数值保持未决。 */
    @Test
    void runtimeObservationKeepsOnDemandAndNativeBoundaries() throws Exception {
        var unit = unit();
        var onDemand = channel(request -> reply(0, "KIND=container\nENGINE=docker\nMODE=ON_DEMAND\n"),
            new ArrayList<>());
        assertEquals(DeploymentRemoteRuntimeObservation.StateType.INSTALLED,
            onDemand.observeRestored(unit).value().orElseThrow().state());
        var nativePeer = new java.util.concurrent.atomic.AtomicInteger();
        var malformed = channel(request -> nativePeer.getAndIncrement() == 0
                ? reply(0, "KIND=deployment\nMODE=DAEMON\n")
                : reply(0, "OWNER=1\nRUNNING=1\nENABLED=enabled\nQUERY_OK=1\nActiveState=active\n"
                + "SubState=running\nResult=success\nExecMainCode=wrong\nExecMainStatus=0\nMainPID=123\n"),
            new ArrayList<>());
        assertEquals(Outcome.UNKNOWN, malformed.observeRestored(unit).outcome());
    }

    /** First rollback requires a unique whole-line completion, while ordinary stop diagnostics remain legal. / 首次回滚要求唯一整行完成标记，普通停机诊断仍合法。 */
    @Test
    void firstRollbackNeverAcceptsForgedOrConflictingCompletion() throws Exception {
        var unit = unit();
        var snapshot = new DeploymentRemoteReleaseSnapshot(Optional.empty(), false, Optional.empty(), "none");
        var good = channel(request -> reply(0, "STOP_AFTER=1\nROLLED_BACK=1\n"), new ArrayList<>());
        assertEquals(Outcome.SUCCEEDED, good.rollback(unit, "d".repeat(64), snapshot, runtime()).outcome());
        var forged = channel(request -> reply(0, "notROLLED_BACK=1\n"), new ArrayList<>());
        assertEquals(Outcome.UNKNOWN, forged.rollback(unit, "d".repeat(64), snapshot, runtime()).outcome());
        var conflict = channel(request -> reply(0, "ROLLED_BACK=0\nROLLED_BACK=1\n"), new ArrayList<>());
        assertEquals(Outcome.UNKNOWN, conflict.rollback(unit, "d".repeat(64), snapshot, runtime()).outcome());
    }

    /** Builds one exact owned unit. / 构建一个精确所属单元。
     * @return reviewed unit / 已审阅单元 */
    private static DeploymentUnitIdentity unit() {
        return DeploymentUnitIdentity.forManaged("demo", new ServerRef("Server-Exact"), "b".repeat(64));
    }

    /** Builds a simple native runtime for snapshot contract selection. / 构建用于快照合同选择的简单原生运行时。
     * @return reviewed runtime / 已审阅运行时 */
    private static DeploymentRuntimeDefinition runtime() {
        return new DeploymentRuntimeDefinition.GoService("1.24", "demo", "main.go",
            new DeploymentHealthCheck.Tcp(8080, 10, 2));
    }

    /** Binds approved fixed commands to a deterministic controlled peer. / 将已审核固定命令绑定到确定性受控对等端。
     * @param answer remote reply / 远端回执
     * @param scripts dispatched scripts / 已发送脚本
     * @return channel under test / 待测通道 */
    private static DeploymentPublicationChannel channel(Function<CommandRequest, OperationResult<CommandResult>> answer,
                                                        List<String> scripts) {
        var task = new TaskContext() {
            public UUID id() {
                return UUID.fromString("11111111-1111-4111-8111-111111111111");
            }

            public CancelToken cancelToken() {
                return () -> false;
            }

            public void progress(String message, Optional<Double> fraction) {
                throw new AssertionError();
            }

            public ResourceLease retain(String kind, String localId, String description) {
                throw new AssertionError();
            }
        };
        var grant = new ApprovalGrant(UUID.randomUUID());
        var interaction = new InteractionService() {
            public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext actual) {
                assertSame(task, actual);
                assertEquals(List.of("Server-Exact"), intent.serverIds());
                return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(grant), Optional.empty());
            }

            public OperationResult<String> requestText(String title, String prompt, boolean secret,
                                                       TaskContext actual) {
                throw new AssertionError();
            }
        };
        RemoteSession session = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
            new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                assertEquals("execute", method.getName());
                assertSame(grant, args[1]);
                var request = (CommandRequest) args[0];
                scripts.add(request.script());
                return answer.apply(request);
            });
        return new DeploymentPublicationChannel(session, interaction, new ServerRef("Server-Exact"), task,
            "f".repeat(64));
    }

    /** Returns a known complete process observation. / 返回已知完整进程观测。
     * @param exit actual exit / 实际退出码
     * @param output exact stdout / 精确标准输出
     * @return observed result / 观测结果 */
    private static OperationResult<CommandResult> reply(int exit, String output) {
        return new OperationResult<>(Outcome.SUCCEEDED,
            Optional.of(new CommandResult(Optional.of(exit), output, "", false)), Optional.empty());
    }

    /** Supplies only fields that the fixed publish command actually reads. / 仅提供固定发布命令实际读取的字段。
     * @param unit exact owner / 精确归属 */
    private record PublishArguments(DeploymentUnitIdentity unit) {
        /** Sends the same reviewed native publication shape. / 发送同一已审阅原生发布形态。
         * @param channel controlled remote boundary / 受控远端边界
         * @return publication observation / 发布观测
         * @throws ApiException if the host rejects admission / 宿主拒绝准入时 */
        OperationResult<Void> publish(DeploymentPublicationChannel channel) throws ApiException {
            var facts = new gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts(
                java.nio.file.Path.of("."), "demo", DeploymentProjectKind.GO_SERVICE,
                gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType.GO_MODULE,
                List.of(), List.of(), List.of());
            return channel.publish(unit, facts, runtime(),
                new DeploymentResourceBindings(List.of(), Optional.of(List.of())), "c".repeat(64), Map.of(),
                "demo-" + "a".repeat(16), "d".repeat(64), "e".repeat(64), false);
        }
    }
}
