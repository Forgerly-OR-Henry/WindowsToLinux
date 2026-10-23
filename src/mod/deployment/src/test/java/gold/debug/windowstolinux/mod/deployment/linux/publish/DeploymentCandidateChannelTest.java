package gold.debug.windowstolinux.mod.deployment.linux.publish;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.appapi.linux.*;
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
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.ResourceLease;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Checks fixed candidate command parsing without claiming a real SSH session. / 验证固定候选命令解析，不声称真实SSH会话。 */
class DeploymentCandidateChannelTest {
    enum VerificationReply {EXACT, BYTES, FULL_SHA, OWNER, BUILD, OWNERSHIP, EXTRA, MISSING, NONZERO, FAILED, TRUNCATED, STDERR}

    @ParameterizedTest
    @EnumSource(VerificationReply.class)
    void verificationSpecificationKeepsFixedObserverAndExactConsumer(VerificationReply shape) throws Exception {
        for (boolean requestAware : List.of(false, true)) {
            var fixture = new VerificationFixture(requestAware, shape);
            var legacy = fixture.channel.verify(fixture.workspace, fixture.archive);
            var specification = fixture.channel.verification(fixture.workspace, fixture.archive);
            var typed = fixture.channel.verify(specification);
            assertEquals(legacy, typed);
            assertEquals(Set.of(VerificationReply.EXACT, VerificationReply.STDERR).contains(shape) ? TaskResult.SUCCEEDED : TaskResult.UNKNOWN, typed.outcome());
            assertEquals(2, fixture.grants.get());
            assertEquals(2, fixture.requests.size(), "Both original interaction entrypoints still dispatch once per call; no Agent workflow is injected");
            assertEquals(fixture.requests.get(0), fixture.requests.get(1));
            assertEquals(specification.guardedScript(), fixture.requests.get(0));
            assertEquals(legacyVerificationBody(fixture), specification.script(), "The original fixed observer bytes are unchanged");
            assertEquals("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\nBYTES=26283\nSHA256=" + fixture.archive.sha256() + "\nOWNER=0\n", specification.expectedOutput());
        }
    }

    enum ForeignBinding {TASK, SESSION, SERVER, REVISION, LAYOUT}

    @ParameterizedTest
    @EnumSource(ForeignBinding.class)
    void verificationSpecificationRejectsForeignChannelBindings(ForeignBinding changed) {
        var fixture = new VerificationFixture(true, VerificationReply.EXACT);
        var spec = fixture.channel.verification(fixture.workspace, fixture.archive);
        var foreign = new DeploymentCandidateChannel(changed == ForeignBinding.SESSION ? fixture.peer() : fixture.session,
            fixture.interaction, changed == ForeignBinding.SERVER ? new ServerId(fixture.server.id()) : fixture.server,
            changed == ForeignBinding.TASK ? fixture.task() : fixture.task, changed == ForeignBinding.REVISION ? "e".repeat(64) : "f".repeat(64),
            new DeploymentBuildBudget(3600), gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard.NONE,
            changed == ForeignBinding.LAYOUT ? DeploymentRemoteLayout.decode(DeploymentRemoteLayout.original().canonical().replace("/var/lib/windowstolinux", "/var/lib/verification-fixture")) : DeploymentRemoteLayout.original());
        assertThrows(IllegalArgumentException.class, () -> foreign.verify(spec));
        assertEquals(0, fixture.grants.get());
        assertTrue(fixture.requests.isEmpty());
    }

    private static String legacyVerificationBody(VerificationFixture fixture) {
        String root = fixture.workspace.candidateRoot();
        String remote = root + "/mutable/source.tar.gz";
        return "set -euo pipefail\n'" + DeploymentRemoteLayout.original().helperPath() + "' candidate-query-task '"
            + fixture.workspace.applicationId() + "' '" + fixture.workspace.candidateId() + "' '" + fixture.task.id() + "'\n"
            + "test -f '" + remote + "' && test ! -L '" + remote + "'\n"
            + "test \"$(realpath -e -- '" + remote + "')\" = '" + remote + "'\n"
            + "printf 'BYTES='; stat -c '%s' -- '" + remote + "'\n"
            + "printf 'SHA256='; sha256sum -- '" + remote + "' | cut -d ' ' -f1\n"
            + "printf 'OWNER='; stat -c '%u' -- '" + remote + "'\n";
    }

    private static final class VerificationFixture {
        final AtomicInteger grants = new AtomicInteger();

        final List<String> requests = new ArrayList<>();

        final ServerId server = new ServerId("server");

        final TaskContext task = task();

        final VerificationReply shape;

        final RemoteSession session = peer();

        final DeploymentSourceArchive archive = new DeploymentSourceArchive((gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot)
            Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot.class}, (p, m, a) -> {
                throw new AssertionError();
            }),
            "a".repeat(64), 26283, 100000, UUID.randomUUID(), "b".repeat(64), "");

        final DeploymentRemoteWorkspace workspace = new DeploymentRemoteWorkspace("demo", archive.sha256());

        final InteractionService interaction;

        final DeploymentCandidateChannel channel;

        VerificationFixture(boolean requestAware, VerificationReply shape) {
            this.shape = shape;
            Class<?> type = requestAware ? gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview.class : InteractionService.class;
            interaction = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
                assertEquals(requestAware ? "approveCommand" : "approve", m.getName());
                assertSame(task, a[a.length - 1]);
                grants.incrementAndGet();
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new ApprovalGrant(UUID.randomUUID())), Optional.empty());
            });
            channel = new DeploymentCandidateChannel(session, interaction, server, task, "f".repeat(64), new DeploymentBuildBudget(3600));
        }

        TaskContext task() {
            return (TaskContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TaskContext.class}, (p, m, a) -> switch (m.getName()) {
                case "id" -> UUID.fromString("11111111-1111-4111-8111-111111111111");
                case "cancelToken" -> (CancelToken) () -> false;
                default -> throw new AssertionError(m.getName());
            });
        }

        RemoteSession peer() {
            return (RemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RemoteSession.class}, (p, m, a) -> {
                assertEquals("execute", m.getName());
                var request = (CommandRequest) a[0];
                assertEquals(Duration.ofSeconds(30), request.timeout());
                assertEquals(4096, request.outputLimitBytes());
                assertEquals(0, request.stdin().length);
                requests.add(request.script());
                String expected = "CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\nBYTES=26283\nSHA256=" + archive.sha256() + "\nOWNER=0\n";
                String output = switch (shape) {
                    case BYTES -> expected.replace("26283", "26284");
                    case FULL_SHA -> expected.replace(archive.sha256(), "a".repeat(16) + "b".repeat(48));
                    case OWNER -> expected.replace("OWNER=0", "OWNER=1");
                    case BUILD -> expected.replace("BUILD_ACTIVE=no", "BUILD_ACTIVE=yes");
                    case OWNERSHIP -> expected.replace("owned", "foreign");
                    case EXTRA -> expected + "EXTRA=1\n";
                    default -> expected;
                };
                if (shape == VerificationReply.MISSING)
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                return new OperationResult<>(shape == VerificationReply.FAILED ? TaskResult.FAILED : TaskResult.SUCCEEDED,
                    Optional.of(new CommandResult(Optional.of(shape == VerificationReply.NONZERO ? 1 : 0), output,
                        shape == VerificationReply.STDERR ? "unchanged informational stderr" : "", shape == VerificationReply.TRUNCATED)),
                    shape == VerificationReply.FAILED ? Optional.of(new Problem(ProblemCode.IO_FAILURE, "CONTROLLED_COMMAND_FAILED", "Controlled failure", Map.of(), Optional.empty())) : Optional.empty());
            });
        }
    }

    @Test
    void failedConfirmationReviewRetainsOnlySafeCauseAndNeverExecutes() throws Exception {
        record ReviewCase(String name, TaskResult outcome, Optional<Problem> problem, Map<String, String> expected) {
        }
        var cases = new ArrayList<ReviewCase>();
        var expected = Map.of("causeOutcome", "FAILED", "causeCode", "CONTRACT_VIOLATION", "causeEvent", "AI_RESPONSE_INVALID");
        var nativeCodes = new ArrayList<String>();
        for (int code = 0; code <= 15; code++) nativeCodes.add(Integer.toString(code));
        nativeCodes.addAll(List.of("", "00", "01", "+1", "-1", "16", "100", "١", "1\n", "secretmarker"));
        for (int index = 0; index < nativeCodes.size(); index++) {
            String code = nativeCodes.get(index);
            var context = new LinkedHashMap<>(Map.of("nativeValidationCode", code, "causeOutcome", "secretmarker",
                "causeCode", "secretmarker", "causeEvent", "secretmarker", "causeNativeValidationCode", "secretmarker",
                "message", "secretmarker", "modelId", "secretmarker", "endpoint", "secretmarker", "header", "secretmarker", "body", "secretmarker"));
            context.put("httpStatus", "secretmarker");
            var projected = new LinkedHashMap<>(expected);
            projected.put("causeNativeValidationCode", index < 16 ? code : "OTHER");
            cases.add(new ReviewCase("native code " + index, TaskResult.FAILED,
                Optional.of(new Problem(ProblemCode.CONTRACT_VIOLATION, "AI_RESPONSE_INVALID", "secretmarker", context, Optional.empty())), projected));
        }
        cases.add(new ReviewCase("no problem", TaskResult.CANCELLED, Optional.empty(),
            Map.of("causeOutcome", "CANCELLED", "causeCode", "ABSENT", "causeEvent", "ABSENT")));
        cases.add(new ReviewCase("foreign event", TaskResult.FAILED,
            Optional.of(new Problem(ProblemCode.IO_FAILURE, "secretmarker", "secretmarker", Map.of("nativeValidationCode", "8"), Optional.empty())),
            Map.of("causeOutcome", "FAILED", "causeCode", "IO_FAILURE", "causeEvent", "OTHER")));
        cases.add(new ReviewCase("foreign problem code", TaskResult.FAILED,
            Optional.of(new Problem(ProblemCode.IO_FAILURE, "AI_RESPONSE_INVALID", "secretmarker", Map.of("nativeValidationCode", "8"), Optional.empty())),
            Map.of("causeOutcome", "FAILED", "causeCode", "IO_FAILURE", "causeEvent", "AI_RESPONSE_INVALID")));
        var noNative = new LinkedHashMap<>(expected);
        noNative.put("causeNativeValidationCode", "OTHER");
        cases.add(new ReviewCase("missing native code", TaskResult.FAILED,
            Optional.of(new Problem(ProblemCode.CONTRACT_VIOLATION, "AI_RESPONSE_INVALID", "secretmarker", Map.of("body", "secretmarker"), Optional.empty())), noNative));
        cases.add(new ReviewCase("original uncertainty", TaskResult.UNKNOWN,
            Optional.of(new Problem(ProblemCode.UNKNOWN, "secretmarker", "secretmarker", Map.of("nativeValidationCode", "8"), Optional.empty())),
            Map.of("causeOutcome", "UNKNOWN", "causeCode", "UNKNOWN", "causeEvent", "OTHER")));
        assertEquals(31, cases.size(), "These are table scenarios inside one JUnit case");
        for (var example : cases) {
            var fixture = new VerificationFixture(true, VerificationReply.EXACT);
            var reviews = new AtomicInteger();
            var executes = new AtomicInteger();
            OperationResult<ApprovalGrant> refused = new OperationResult<>(example.outcome(), Optional.empty(), example.problem());
            InteractionService interaction = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview.class}, (p, method, args) -> {
                    assertEquals("approveCommand", method.getName());
                    assertSame(fixture.task, args[args.length - 1]);
                    assertEquals("deployment.candidate.verify.v1", ((ActionIntent) args[0]).semantics().orElseThrow().operation());
                    assertEquals(0, ((CommandRequest) args[1]).stdin().length);
                    reviews.incrementAndGet();
                    return refused;
                });
            RemoteSession session = (RemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RemoteSession.class}, (p, method, args) -> {
                executes.incrementAndGet();
                throw new AssertionError("No remote method may run after an unsuccessful confirmation review");
            });
            var channel = new DeploymentCandidateChannel(session, interaction, fixture.server, fixture.task, "f".repeat(64), new DeploymentBuildBudget(3600));
            var observed = channel.verify(fixture.workspace, fixture.archive);
            assertEquals(TaskResult.UNKNOWN, observed.outcome(), example.name());
            assertTrue(observed.value().isEmpty());
            var problem = observed.problem().orElseThrow();
            assertEquals(ProblemCode.UNKNOWN, problem.code());
            assertEquals("DEPLOYMENT_UPLOAD_REMOTE_UNCONFIRMED", problem.eventCode());
            assertEquals(example.expected(), problem.context(), example.name());
            assertFalse(problem.toString().contains("secretmarker"));
            assertEquals(example.outcome(), refused.outcome());
            assertEquals(example.problem(), refused.problem(), "Retain the original failure; it is not a cleanup or not-sent proof");
            assertEquals(1, reviews.get());
            assertEquals(0, executes.get());
        }
    }

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
