package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import gold.debug.windowstolinux.appapi.task.ActionIntent;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskRequest;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ReferenceResolution;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.CancelToken;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.junit.jupiter.api.Test;

/** Exercises original H2 checkpoint reconciliation with a controlled fixed remote peer. / 用受控固定远端对等端验证原H2检查点核对。 */
class DeploymentStandardPublicationRecoveryTest extends DeploymentRuntimeFixture {
    /** Exact original controlled server. / 精确原受控服务器。 */
    private static final ServerRef SERVER = new ServerRef("server-publication");

    /** First candidate-cleanup approval is refused before dispatch. / 第一次候选清理授权在发送前拒绝。 */
    private final AtomicInteger cleanupReviews = new AtomicInteger();

    /** Starts actual CLASS/H2 task admission with a deterministic host approval. / 以确定性宿主审批启动真实CLASS/H2任务准入。
     * @return actual runtime / 真实运行时
     * @throws Exception if platform startup fails / 平台启动失败时 */
    @Override
    protected PlatformRuntime open() throws Exception {
        var runtime = PlatformRuntime.open(root.resolve("data"), PROTECTION);
        if (runtime.security().securityState().configured())
            runtime.security().unlock(PASSWORD.toCharArray());
        else
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        runtime.setInteractionPresenter(new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerRef server,
                                                           TaskSnapshot task, CancelToken cancellation) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerRef server, TaskSnapshot task,
                                                       CancelToken cancellation) {
                throw new AssertionError();
            }

            public String transferProgress(TransferProgress progress) {
                throw new AssertionError();
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation) {
                assertEquals(List.of(SERVER.id()), intent.serverIds());
                if (intent.semantics().orElseThrow().operation().equals("deployment.publication.candidate-cleanup.v1"))
                    return cleanupReviews.incrementAndGet() != 1;
                return true;
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                               CancelToken cancellation) {
                throw new AssertionError();
            }
        });
        runtime.setSourcePicker((title, task, cancellation) -> {
            throw new AssertionError("No source selection expected");
        });
        runtime.started();
        return runtime;
    }

    /** A definite review refusal restores the prior checkpoint; the next explicit reconciliation may request approval again. / 明确审核拒绝恢复先前检查点；下次显式核对可重新请求授权。 */
    @Test
    void preDispatchRefusalCanBeReviewedAgainWithoutReplayingAnUnknownEffect() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile.fromStored(
                    new StoredServerProfile(SERVER.id(), "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var real = runtime.modContexts().get(OWNER).context();
            var candidate = new AtomicBoolean(true);
            var sent = new AtomicInteger();
            var observed = new AtomicReference<Throwable>();
            RemoteSession remote = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close"))
                        return null;
                    assertEquals("execute", method.getName());
                    String script = ((CommandRequest) args[0]).script();
                    sent.incrementAndGet();
                    if (script.contains("publication-original-status"))
                        return reply(status(candidate.get()));
                    assertTrue(script.contains("candidate-cleanup-task"), script);
                    candidate.set(false);
                    return reply("CANDIDATE_CLEANED=1\n");
                });
            var bound = withRemote(real, remote);
            var handle = real.tasks().submit(new TaskRequest("publication-recovery-probe", "Recover original candidate",
                List.of(SERVER.id()), true), task -> {
                try {
                    var identity = new DeploymentExecutionIdentity(UUID.randomUUID(), task.id(),
                        Optional.empty(), "demo", SERVER,
                        DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "f".repeat(64));
                    var repository = new DeploymentExecutionRepository(real.db());
                    var intent = DeploymentReferenceIntent.server(SERVER, "application:demo");
                    repository.begin(task, identity, "deployment-publication", List.of(intent),
                        Optional.empty(), Instant.parse("2026-01-01T00:00:00Z"));
                    var reference = real.servers().reserve(SERVER, intent.businessKey(), task);
                    repository.recordReference(task, identity.executionId(), intent, reference.id(),
                        Instant.parse("2026-01-01T00:00:00.500Z"));
                    repository.startEffects(task, identity.executionId(),
                        Instant.parse("2026-01-01T00:00:01Z"));
                    var unit = DeploymentUnitIdentity.forManaged("demo", SERVER, "b".repeat(64));
                    DeploymentPublicationCleanupJournal.begin(real, task, unit, "a".repeat(64), "f".repeat(64),
                        new DeploymentRuntimeDefinition.GoService("1.24", "demo", "main.go",
                            new DeploymentHealthCheck.Tcp(8080, 10, 2)));
                    var recovery = new DeploymentStandardPublicationRecovery(bound);
                    var record = repository.findForTask(task).orElseThrow();
                    var finalResult = recovery.reconcile(task, record).orElseThrow();
                    assertEquals(Outcome.FAILED, finalResult.outcome());
                    assertEquals(1, cleanupReviews.get());
                    assertEquals(1, sent.get(), "Only read-only status was dispatched before review refusal");
                    assertEquals("MAINTENANCE_ENDED", new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(real.tasks().findCheckpoint(task, DeploymentPublicationCleanupJournal.KEY)
                            .orElseThrow().snapshot().payload())
                        .path("phase").asText());
                    assertTrue(candidate.get());
                    assertEquals(Outcome.FAILED, recovery.reconcile(task, record).orElseThrow().outcome());
                    assertEquals(2, cleanupReviews.get());
                    assertEquals(4, sent.get(), "Two status reads and one cleanup use the original task");
                    assertFalse(candidate.get());
                    assertTrue(real.tasks().findCheckpoint(task, DeploymentPublicationCleanupJournal.KEY)
                        .orElseThrow().snapshot().released());
                    repository.recordUncommittedOutcome(task, identity.executionId(), Outcome.FAILED,
                        finalResult.problem().orElseThrow(), Instant.parse("2026-01-01T00:00:02Z"));
                    real.servers().resolve(reference.id(), ReferenceResolution.NOT_REFERENCED, task);
                    repository.finishCleanup(task, identity.executionId(),
                        Instant.parse("2026-01-01T00:00:03Z"));
                    return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Throwable failure) {
                    observed.set(failure);
                    throw failure;
                }
            });
            OperationResult<Void> result;
            try {
                result = handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException unfinished) {
                throw new AssertionError("Recovery probe unfinished: " + handle.snapshot(),
                    observed.get() == null ? unfinished : observed.get());
            }
            assertEquals(Outcome.SUCCEEDED, result.outcome());
            assertNull(observed.get(), () -> String.valueOf(observed.get()));
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND resource_kind='CUSTOM'"));
        }
    }

    /** Replaces only the borrowed remote peer, preserving real task, H2, source and review capabilities. / 仅替换借用远端对等端，保留真实任务、H2、来源和审核能力。
     * @param real original activation / 原启用
     * @param remote controlled peer / 受控对等端
     * @return delegated activation / 委托启用 */
    private static ModContext withRemote(ModContext real, RemoteSession remote) {
        LinuxService linux = (LinuxService) Proxy.newProxyInstance(LinuxService.class.getClassLoader(),
            new Class<?>[]{LinuxService.class}, (proxy, method, args) -> {
                if (method.getName().equals("pendingTransfers"))
                    return List.of();
                assertEquals("connect", method.getName());
                assertEquals(SERVER, args[0]);
                return remote;
            });
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> {
                if (method.getName().equals("linux"))
                    return linux;
                try {
                    return method.invoke(real, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
    }

    /** Fixed complete read-only response. / 固定完整只读响应。
     * @param owned whether the original candidate still exists / 原候选是否仍存在
     * @return exact response / 精确响应 */
    private static String status(boolean owned) {
        return "PUBLICATION_STATUS=1\nCURRENT=none\nCURRENT_KIND=none\nCURRENT_MODE=none\n"
            + "SNAPSHOT=none\nSNAPSHOT_OLD=none\nSNAPSHOT_KIND=none\nSNAPSHOT_RUNNING=none\n"
            + "MAINTENANCE=none\nCANDIDATE=" + (owned ? "owned" : "absent")
            + "\nBUILD_ACTIVE=no\nUNIT_PRESENT=no\nCONTAINER_PRESENT=not-applicable\nRETAINED_COUNT=0\n";
    }

    /** Complete controlled process receipt. / 完整受控进程回执。
     * @param output exact helper output / 精确helper输出
     * @return successful remote observation / 成功远端观测 */
    private static OperationResult<CommandResult> reply(String output) {
        return new OperationResult<>(Outcome.SUCCEEDED,
            Optional.of(new CommandResult(Optional.of(0), output, "", false)), Optional.empty());
    }
}
