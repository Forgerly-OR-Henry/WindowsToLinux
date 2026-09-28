package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationEntry;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationScopeType;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationValue;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.*;
import gold.debug.windowstolinux.mod.deployment.source.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Confirms the original CLASS/H2 task and real host review around controlled remote build replies. / 在受控远端构建回执周围确认原 CLASS/H2 任务及真实宿主审核。 */
class DeploymentStandardBuildExecutorTest extends DeploymentRuntimeFixture {
    /** One concrete host or remote boundary exercised by the shared CLASS task fixture. / 由共享CLASS任务夹具验证的单个宿主或远端边界。 */
    private enum ScenarioType {
        SUCCESS, COMPILER_FAILURE, WRONG_BRANCH, COMPATIBLE_THEN_SUCCESS, COMPATIBLE_TWICE, FIRST_BUILD_UNKNOWN
    }

    /** Exact host-reviewed actions. / 经宿主审核的精确动作。 */
    private final List<ActionIntent> reviewed = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Uses actual host Interaction while keeping transport local. / 使用真实宿主 Interaction，传输保持本机受控。 */
    @Override
    protected PlatformRuntime open() throws Exception {
        var runtime = PlatformRuntime.open(root.resolve("data"), PROTECTION);
        if (runtime.security().securityState().configured())
            runtime.security().unlock(PASSWORD.toCharArray());
        else
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        runtime.setInteractionPresenter(new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerRef server,
                    TaskSnapshot task, CancellationToken cancellation) {
                throw new AssertionError("No transfer in this build-only test");
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerRef server, TaskSnapshot task,
                    CancellationToken cancellation) {
                throw new AssertionError("No transfer in this build-only test");
            }

            public String transferProgress(TransferProgress progress) {
                throw new AssertionError("No transfer in this build-only test");
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancellationToken cancellation) {
                reviewed.add(intent);
                return true;
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                    CancellationToken cancellation) {
                throw new AssertionError("No text prompt expected");
            }
        });
        runtime.setSourcePicker((title, task, cancellation) -> selectedSourceDirectory());
        runtime.started();
        return runtime;
    }

    /** Actual source selection root. / 实际源码选择根。 */
    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    /** Executes actual source/plan/task/approval and checks exact prepared tools and tar identity. / 执行真实来源、计划、任务与审核，并核对精确准备工具及tar身份。
     * @throws Exception if runtime or source setup fails / 运行或来源设置失败时 */
    @ParameterizedTest
    @EnumSource(ScenarioType.class)
    void actualClassTaskBindsOriginalArchiveAndToolsBeforeRestrictedBuild(ScenarioType scenario) throws Exception {
        Path input = selectedSourceDirectory();
        Files.createDirectories(input);
        boolean alternative = scenario == ScenarioType.COMPATIBLE_THEN_SUCCESS
                || scenario == ScenarioType.COMPATIBLE_TWICE || scenario == ScenarioType.FIRST_BUILD_UNKNOWN;
        Files.writeString(input.resolve("go.mod"),
                "module example.test/service\n\ngo " + (alternative ? "1.24.9" : "1.24") + "\n");
        Files.writeString(input.resolve("go.sum"), "example.test/dependency v1.0.0 h1:fixture\n");
        Files.writeString(input.resolve("main.go"), "package main\nfunc main() {}\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = new ServerRef("server-build");
            runtime.servers().saveServerProfile(
                    ServerProfile.fromStored(
                            new StoredServerProfile(server.id(), "127.0.0.1", 22, "root", "fixture-password")),
                    "fixture-password".toCharArray());
            var calls = new AtomicInteger();
            var builds = new AtomicInteger();
            var bound = new AtomicReference<DeploymentResolvedToolchains>();
            var observedBuild = new AtomicReference<OperationResult<DeploymentVerifiedBuildResult>>();
            var callbackFinished = new CountDownLatch(1);
            var catalog = DeploymentToolchainCatalog.defaults();
            RemoteSession session = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                    new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("execute"))
                            throw new AssertionError("Borrowed session must not be closed or inspected");
                        calls.incrementAndGet();
                        CommandRequest command = (CommandRequest) args[0];
                        String script = command.script();
                        if (script.contains("build-preflight"))
                            return reply("");
                        if (script.contains("prepare-toolchains 'prepare'")) {
                            if (scenario == ScenarioType.WRONG_BRANCH)
                                return reply("TOOLCHAIN=GO|9.99.1|/usr/local/lib/windowstolinux/toolchains/versions/go-"
                                        + "b".repeat(64) + "|MANAGED|https://official.example/go|" + "b".repeat(64)
                                        + "\n");
                            String version = script.contains("'1.25'") ? "1.25.1" : alternative ? "1.24.9" : "1.24.1";
                            return reply("TOOLCHAIN=GO|" + version
                                    + "|/usr/local/lib/windowstolinux/toolchains/versions/go-" + "b".repeat(64)
                                    + "|MANAGED|https://official.example/go|" + "b".repeat(64) + "\n");
                        }
                        if (script.contains("prepare-toolchains 'bind'")) {
                            String encoded = script.substring(script.indexOf("'bind' '") + 8);
                            encoded = encoded.substring(0, encoded.indexOf('\''));
                            var set = DeploymentToolchainBindingCodec
                                    .decode(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
                            bound.set(set);
                            return reply("TOOLCHAIN_BINDING=" + DeploymentToolchainBindingCodec.identity(set) + "\n");
                        }
                        assertTrue(script.startsWith("exec /usr/bin/python3 -I -c "));
                        assertEquals(65536, command.outputLimitBytes());
                        assertTrue(new String(command.stdin(), StandardCharsets.UTF_8).contains("WTL_GO_VERSION"));
                        assertTrue(new String(command.stdin(), StandardCharsets.UTF_8).contains("BUILD_MARKER"));
                        assertFalse(new String(command.stdin(), StandardCharsets.UTF_8).contains("RUNTIME_MARKER"));
                        int buildNumber = builds.incrementAndGet();
                        if (scenario == ScenarioType.FIRST_BUILD_UNKNOWN)
                            return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                                    Optional.of(new Problem(ProblemCode.UNKNOWN, "REMOTE_BUILD_UNCONFIRMED",
                                            "Build receipt lost", Map.of(), Optional.empty())));
                        String diagnostic = "compile error: invalid module\ntoken=<redacted>\n";
                        String encoded = Base64.getEncoder()
                                .encodeToString(diagnostic.getBytes(StandardCharsets.UTF_8));
                        boolean failed = scenario == ScenarioType.COMPILER_FAILURE
                                || scenario == ScenarioType.COMPATIBLE_TWICE
                                || scenario == ScenarioType.COMPATIBLE_THEN_SUCCESS && buildNumber == 1;
                        return reply("WTL_BUILD_REPORT=1\nEXIT=" + (failed ? 1 : 0) + "\nBYTES=100\nSHA256="
                                + "c".repeat(64) + "\nUID=1001\nENGINE=ordinary\nTOOL=GO_MODULE\nCOMPAT="
                                + (alternative ? 1 : 0) + "\nLIMIT=-\nCONFLICT=0\nHEAD=" + (failed ? encoded : "")
                                + "\nTAIL=" + (failed ? encoded : "") + "\n");
                    });
            var context = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(
                    new TaskRequest("deployment-standard-build", "Build source", List.of(server.id()), true), task -> {
                        try {
                            var selection = context.source().selectDirectory("Source", task);
                            try (var material = new DeploymentSourcePreparer(context.source()).prepare(selection, 65536,
                                    100, task);
                                    var archive = new DeploymentSourceArchiver(context.source()).prepare(material,
                                            65536, task)) {
                                var analysis = new DeploymentStandardAnalyzer().analyze(material, "Reviewed Go",
                                        DeploymentProjectKind.GO_SERVICE, Optional.empty(), false, task);
                                assertEquals(Outcome.SUCCEEDED, analysis.outcome());
                                var facts = analysis.value().orElseThrow().assessment().facts().orElseThrow();
                                var request = new ReviewedDeploymentRequest(server, facts, material, archive,
                                        DeploymentConfigurationSnapshot.create(facts.applicationId(), 1, "v1",
                                                Instant.now(),
                                                List.of(new DeploymentConfigurationEntry("BUILD_MARKER",
                                                        DeploymentConfigurationScopeType.BUILD,
                                                        new DeploymentConfigurationValue.Text("reviewed")),
                                                        new DeploymentConfigurationEntry("RUNTIME_MARKER",
                                                                DeploymentConfigurationScopeType.RUNTIME,
                                                                new DeploymentConfigurationValue.Text(
                                                                        "not-exported")))),
                                        List.of(), new DeploymentResourceBindings(List.of(), Optional.of(List.of())),
                                        new DeploymentRuntimeDefinition.GoService("1.24", facts.applicationId(),
                                                "main.go", new DeploymentHealthCheck.Tcp(8080, 10, 2)),
                                        Optional.empty(), DeploymentBuildLimits.defaultNonRoot(),
                                        new DeploymentReviewConsent(facts.applicationId(), server.id(),
                                                material.snapshot().sha256(), archive.sha256(), Instant.now()),
                                        false, true);
                                var plan = new DeploymentStandardPlanner().plan(material,
                                        analysis.value().orElseThrow(), request, task);
                                assertEquals(Outcome.SUCCEEDED, plan.outcome());
                                var workspace = new DeploymentRemoteWorkspace(facts.applicationId(), archive.sha256());
                                var result = new DeploymentStandardBuildExecutor().build(plan.value().orElseThrow(),
                                        workspace, session, context.interaction(), task);
                                observedBuild.set(result);
                                if (scenario == ScenarioType.COMPILER_FAILURE) {
                                    assertEquals(Outcome.FAILED, result.outcome(), result.toString());
                                    var problem = result.problem().orElseThrow();
                                    assertEquals("DEPLOYMENT_BUILD_FAILED", problem.eventCode());
                                    assertTrue(problem.message().contains("compile error: invalid module"));
                                    assertTrue(problem.context().get("diagnostic").contains("token=<redacted>"));
                                    assertFalse(problem.message().contains("ActualSecret"));
                                } else if (scenario == ScenarioType.SUCCESS
                                        || scenario == ScenarioType.COMPATIBLE_THEN_SUCCESS) {
                                    assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
                                    assertEquals(archive.sha256(), result.value().orElseThrow().archiveSha256());
                                    assertEquals(workspace.candidateId(), result.value().orElseThrow().candidateId());
                                    assertEquals(bound.get(), result.value().orElseThrow().toolchains());
                                    if (alternative)
                                        assertEquals("1.25.1", result.value().orElseThrow().toolchains().selections()
                                                .getFirst().version().text());
                                } else if (scenario == ScenarioType.COMPATIBLE_TWICE) {
                                    assertEquals(Outcome.FAILED, result.outcome(), result.toString());
                                    assertEquals(ProblemCode.INCOMPATIBLE, result.problem().orElseThrow().code());
                                    assertEquals("DEPLOYMENT_BUILD_CANDIDATES_EXHAUSTED",
                                            result.problem().orElseThrow().eventCode());
                                } else {
                                    assertEquals(Outcome.UNKNOWN, result.outcome(), result.toString());
                                }
                                callbackFinished.countDown();
                                return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                            }
                        } catch (Throwable caught) {
                            failure.set(caught);
                            callbackFinished.countDown();
                            throw caught;
                        }
                    });
            assertTrue(callbackFinished.await(20, TimeUnit.SECONDS), "callback did not return");
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            OperationResult<Void> done;
            try {
                done = handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                throw new AssertionError("Task did not complete: snapshot=" + handle.snapshot() + ", build="
                        + observedBuild.get() + ", calls=" + calls.get(), timeout);
            }
            assertEquals(Outcome.SUCCEEDED, done.outcome());
            assertEquals(scenario == ScenarioType.WRONG_BRANCH
                    ? 2
                    : alternative && scenario != ScenarioType.FIRST_BUILD_UNKNOWN ? 7 : 4, calls.get());
            var operations = List.of("deployment.build.preflight.v1", "deployment.toolchain.prepare.v1",
                    "deployment.toolchain.bind.v1", "deployment.build.run.v1");
            var expected = scenario == ScenarioType.WRONG_BRANCH
                    ? operations.subList(0, 2)
                    : alternative && scenario != ScenarioType.FIRST_BUILD_UNKNOWN
                            ? List.of(operations.get(0), operations.get(1), operations.get(2), operations.get(3),
                                    operations.get(1), operations.get(2), operations.get(3))
                            : operations;
            assertEquals(expected,
                    reviewed.stream().map(intent -> intent.semantics().orElseThrow().operation()).toList());
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
        }
    }

    /** Returns a confirmed zero-exit public command observation. / 返回已确认退出零的公开命令观测。
     * @param stdout exact remote text / 精确远端文本
     * @return successful command result / 成功命令结果 */
    private static OperationResult<CommandResult> reply(String stdout) {
        return new OperationResult<>(Outcome.SUCCEEDED,
                Optional.of(new CommandResult(Optional.of(0), stdout, "", false)), Optional.empty());
    }
}
