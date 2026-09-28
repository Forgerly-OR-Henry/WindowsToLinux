package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.SecretRef;
import gold.debug.windowstolinux.appapi.server.ReferenceResolution;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSecretRepository;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.plan.DeploymentReviewedReleaseIdentity;
import gold.debug.windowstolinux.mod.deployment.source.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Executes the real original publication task, source handles, H2 and host review against a controlled peer. / 用受控对等端执行真实原发布任务、来源句柄、H2和宿主审核。 */
class DeploymentStandardPublicationExecutorTest extends DeploymentRuntimeFixture {
    /** Three distinct original remote outcomes. / 三种不同的原远端结果。 */
    private enum ScenarioType {
        SUCCESS, SECOND_CONFIG_UNKNOWN, PUBLISH_UNKNOWN, HEALTH_FAILED_ROLLBACK, HEALTH_FAILED_ROLLBACK_OLD_MODE, HEALTH_FAILED_ROLLBACK_CLOSE_UNKNOWN, ROLLBACK_UNKNOWN, RETAIN_UNKNOWN, COMMIT_WINDOW, PURPOSE_PRESENT, PURPOSE_UNKNOWN, PURPOSE_MISMATCH
    }

    /** Actual host-reviewed grants. / 宿主实际审核的授权。 */
    private final Map<ApprovalGrant, ActionIntent> grants = new ConcurrentHashMap<>();

    /** Optional controlled loopback port for a later cross-process recovery probe. / 后续跨进程恢复探针可用的受控回环端口。 */
    private int recoveryPort = 22;

    /** Starts an actual CLASS/H2 runtime with controlled host interactions. / 启动带受控宿主交互的真实CLASS/H2运行时。
     * @return actual platform / 真实平台
     * @throws Exception if setup fails / 设置失败时 */
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
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerRef server, TaskSnapshot task,
                    CancellationToken cancellation) {
                throw new AssertionError();
            }

            public String transferProgress(TransferProgress progress) {
                return "Controlled upload";
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancellationToken cancellation) {
                assertEquals(List.of("server-publication"), intent.serverIds());
                return true;
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                    CancellationToken cancellation) {
                throw new AssertionError();
            }
        });
        runtime.setSourcePicker((title, task, cancellation) -> selectedSourceDirectory());
        runtime.started();
        return runtime;
    }

    /** Returns one actual source selection. / 返回一项真实来源选择。
     * @return original directory / 原目录 */
    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    /** Checks source to publication ordering and original-task cleanup for known and unknown remote effects. / 检查来源到发布顺序及已知与未知远端副作用的原任务清理。
     * @param scenario exact remote outcome / 精确远端结果
     * @throws Exception if actual platform setup fails / 真实平台准备失败时 */
    @ParameterizedTest
    @EnumSource(value = ScenarioType.class, names = {"SUCCESS", "HEALTH_FAILED_ROLLBACK",
            "HEALTH_FAILED_ROLLBACK_OLD_MODE"})
    void originalTaskPublishesOrRetainsRemoteEvidence(ScenarioType scenario) throws Exception {
        runScenario(scenario, false);
    }

    /** Rejects missing or mismatched persisted purpose before any remote publication. / 在任何远端发布前拒绝缺失或不匹配的已持久用途。
     * @param scenario exact purpose defect / 精确用途缺陷
     * @throws Exception if the real original task fails / 真实原任务失败时 */
    @ParameterizedTest
    @EnumSource(value = ScenarioType.class, names = {"PURPOSE_UNKNOWN", "PURPOSE_MISMATCH"})
    void savedPurposeIsRequiredForSinglePublication(ScenarioType scenario) throws Exception {
        runScenario(scenario, false);
    }

    /** Publishes a real stored credential whose identifier is not its purpose. / 发布标识与用途不同的真实已存凭据。
     * @throws Exception if the original publication cannot finish / 原发布无法结束时 */
    @Test
    void exactStoredPurposePermitsOriginalSecretPublication() throws Exception {
        runScenario(ScenarioType.PURPOSE_PRESENT, false);
    }

    /** Verifies the retained original task in its own JVM, whose exit models an interrupted host. / 在独立JVM验证保留的原任务，其退出模拟宿主中断。
     * @throws Exception if the controlled child cannot finish / 受控子进程无法完成时 */
    @ParameterizedTest
    @EnumSource(value = ScenarioType.class, names = {"SECOND_CONFIG_UNKNOWN", "PUBLISH_UNKNOWN",
            "HEALTH_FAILED_ROLLBACK_CLOSE_UNKNOWN", "ROLLBACK_UNKNOWN", "RETAIN_UNKNOWN"})
    void unknownPublicationRetainsOriginalEvidenceWithoutNormalShutdown(ScenarioType scenario) throws Exception {
        var javaBinary = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        var process = new ProcessBuilder(javaBinary.toString(), "-cp", System.getProperty("java.class.path"),
                getClass().getName(), "--unknown", root.toString(), scenario.name()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(50, TimeUnit.SECONDS), "Isolated original-task probe timed out");
            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("ORIGINAL_UNKNOWN_VERIFIED"), output);
        } finally {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Task-started probe process did not exit");
                }
            }
        }
    }

    /** Runs only the controlled unknown case when launched by the parent test. / 仅在父测试启动时运行受控未知场景。
     * @param args fixed child marker, directory and scenario / 固定子进程标记、目录与场景 */
    public static void main(String[] args) {
        if ((args.length != 3 && args.length != 4) || !args[0].equals("--unknown"))
            throw new IllegalArgumentException("Isolated publication probe only");
        try {
            var probe = new DeploymentStandardPublicationExecutorTest();
            probe.root = Path.of(args[1]);
            if (args.length == 4)
                probe.recoveryPort = Integer.parseInt(args[3]);
            probe.runScenario(ScenarioType.valueOf(args[2]), true);
            System.out.println("ORIGINAL_UNKNOWN_VERIFIED");
            System.exit(0);
        } catch (Throwable failed) {
            failed.printStackTrace();
            System.exit(1);
        }
    }

    /** Performs one actual task with a controlled SSH peer. / 使用受控SSH对等端执行一个真实任务。
     * @param scenario exact remote outcome / 精确远端结果
     * @param interruptedHost whether normal shutdown is intentionally unavailable / 正常关闭是否按设计不可用
     * @throws Exception if the original fixture fails / 原夹具失败时 */
    private void runScenario(ScenarioType scenario, boolean interruptedHost) throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("go.mod"), "module example.test/service\n\ngo 1.24\n");
        Files.writeString(selectedSourceDirectory().resolve("go.sum"), "example.test/dependency v1 h1:fixture\n");
        Files.writeString(selectedSourceDirectory().resolve("main.go"), "package main\nfunc main() {}\n");
        enable();
        var runtime = open();
        try {
            start(runtime, true);
            var server = new ServerRef("server-publication");
            runtime.servers().saveServerProfile(ServerProfile.fromStored(
                    new StoredServerProfile(server.id(), "127.0.0.1", recoveryPort, "root", "fixture-password")),
                    "fixture-password".toCharArray());
            var actions = new CopyOnWriteArrayList<String>();
            var archiveSha = new AtomicReference<String>();
            var archiveBytes = new AtomicLong();
            var archivePath = new AtomicReference<Path>();
            var candidate = new AtomicReference<DeploymentRemoteWorkspace>();
            var callbackEnded = new CountDownLatch(1);
            RemoteSession session = peer(scenario, actions, archiveSha, archiveBytes, archivePath, candidate,
                    callbackEnded);
            var real = runtime.modContexts().get(OWNER).context();
            var bound = withControlledSession(real, session, runtime, scenario);
            var original = new DeploymentReferenceFlow(bound);
            var executor = new DeploymentStandardPublicationExecutor(bound, original);
            var unit = DeploymentUnitIdentity.forManaged("service", server, "b".repeat(64));
            var reference = new DeploymentSecretReference("password-id", 1);
            boolean purposeCase = scenario == ScenarioType.PURPOSE_PRESENT || scenario == ScenarioType.PURPOSE_UNKNOWN
                    || scenario == ScenarioType.PURPOSE_MISMATCH;
            boolean purposeDefect = scenario == ScenarioType.PURPOSE_UNKNOWN
                    || scenario == ScenarioType.PURPOSE_MISMATCH;
            var secret = purposeCase ? purposeSecret(real, reference, scenario == ScenarioType.PURPOSE_UNKNOWN) : null;
            var reviewedSecret = purposeCase && scenario == ScenarioType.PURPOSE_MISMATCH
                    ? new SecretRef(secret.id(), "deployment.wrong")
                    : secret;
            var credentials = purposeCase
                    ? Map.of(reference, reviewedSecret)
                    : Map.<DeploymentSecretReference, SecretRef>of();
            var handle = executor.submit(unit, Optional.empty(), credentials, task -> {
                if (recoveryPort != 22)
                    real.linux().connect(server, task);
                var selection = real.source().selectDirectory("Source", task);
                var material = new DeploymentSourcePreparer(real.source()).prepare(selection, 65536, 100, task);
                var archive = new DeploymentSourceArchiver(real.source()).prepare(material, 65536, task);
                archiveSha.set(archive.sha256());
                archiveBytes.set(archive.byteCount());
                archivePath.set(archive.path());
                var analysis = new DeploymentStandardAnalyzer().analyze(material, "service",
                        DeploymentProjectKind.GO_SERVICE, Optional.empty(), false, task);
                var facts = analysis.value().orElseThrow().assessment().facts().orElseThrow();
                candidate.set(new DeploymentRemoteWorkspace(facts.applicationId(), archive.sha256()));
                assertEquals(unit.id(), facts.applicationId());
                var request = new ReviewedDeploymentRequest(server, facts, material, archive,
                        DeploymentConfigurationSnapshot.create(facts.applicationId(), 1, "v1", Instant.now(),
                                List.of()),
                        purposeCase ? List.of(reference) : List.of(),
                        new DeploymentResourceBindings(List.of(), Optional.of(List.of())),
                        new DeploymentRuntimeDefinition.GoService("1.24", facts.applicationId(), "main.go",
                                new DeploymentHealthCheck.Tcp(8080, 10, 2)),
                        Optional.empty(), DeploymentBuildLimits.defaultNonRoot(),
                        new DeploymentReviewConsent(facts.applicationId(), server.id(), material.snapshot().sha256(),
                                archive.sha256(), Instant.now()),
                        false, true);
                if (scenario == ScenarioType.SUCCESS)
                    assertReleaseIdentity(real, task, request);
                var plan = new DeploymentStandardPlanner().plan(material, analysis.value().orElseThrow(), request,
                        task);
                return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(plan.value().orElseThrow()),
                        Optional.empty());
            });
            if (purposeDefect) {
                var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                assertEquals(Outcome.FAILED, result.outcome(), result.toString());
                assertEquals(scenario == ScenarioType.PURPOSE_UNKNOWN
                        ? "DEPLOYMENT_SECRET_PURPOSE_UNAVAILABLE"
                        : "DEPLOYMENT_PREPARATION_INVALID", result.problem().orElseThrow().eventCode());
                assertTrue(actions.isEmpty(), actions.toString());
                assertEquals(0, countMod(runtime, "deployment_release"));
                assertEquals(0,
                        scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                return;
            }
            assertTrue(callbackEnded.await(25, TimeUnit.SECONDS), () -> "Remote callback: " + handle.snapshot());
            if (scenario == ScenarioType.SUCCESS || scenario == ScenarioType.PURPOSE_PRESENT) {
                OperationResult<Void> result;
                try {
                    result = handle.completion().toCompletableFuture().get(12, TimeUnit.SECONDS);
                } catch (TimeoutException unfinished) {
                    throw new AssertionError(
                            "Publication unfinished: " + handle.snapshot() + ", actions=" + actions + ", releases="
                                    + countMod(runtime, "deployment_release") + ", resources="
                                    + scalar(runtime,
                                            "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"),
                            unfinished);
                }
                assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
                assertEquals(1, countMod(runtime, "deployment_release"));
                if (scenario == ScenarioType.PURPOSE_PRESENT) {
                    assertNotEquals(reference.identifier(), reviewedSecret.purpose());
                    assertEquals(1, countReleaseSecrets(runtime));
                }
                assertEquals(0,
                        scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                assertEquals(List.of("maintenance-end", "retain", "candidate-cleanup"),
                        actions.subList(actions.size() - 3, actions.size()));
            } else if (scenario == ScenarioType.HEALTH_FAILED_ROLLBACK
                    || scenario == ScenarioType.HEALTH_FAILED_ROLLBACK_OLD_MODE) {
                var result = handle.completion().toCompletableFuture().get(12, TimeUnit.SECONDS);
                assertEquals(Outcome.FAILED, result.outcome(), result.toString());
                assertEquals(0, countMod(runtime, "deployment_release"));
                assertTrue(actions.contains("rollback"), actions.toString());
                assertTrue(actions.contains("candidate-cleanup"), actions.toString());
                assertEquals(0,
                        scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            } else {
                for (int attempt = 0; attempt < 200 && handle.snapshot().state() != TaskState.UNKNOWN; attempt++)
                    Thread.sleep(10);
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), handle.snapshot().toString());
                assertEquals(scenario == ScenarioType.RETAIN_UNKNOWN ? 1 : 0, countMod(runtime, "deployment_release"));
                if (scenario == ScenarioType.COMMIT_WINDOW) {
                    assertEquals(0, countMod(runtime, "deployment_release"));
                    assertTrue(originalCheckpointReleased(runtime, handle.id()));
                    assertEquals(1, countExecutionOutcome(runtime, "UNKNOWN"));
                    assertFalse(actions.contains("rollback"));
                } else if (scenario == ScenarioType.RETAIN_UNKNOWN) {
                    assertEquals(List.of("maintenance-end", "retain"),
                            actions.subList(actions.size() - 2, actions.size()));
                    assertFalse(actions.contains("candidate-cleanup"));
                    assertTrue(pendingCandidate(runtime, handle.id(), candidate.get().candidateId(),
                            "RETAIN_MAY_HAVE_RUN"));
                    assertEquals(1, countExecutionOutcome(runtime, "SUCCEEDED"));
                } else if (scenario == ScenarioType.SECOND_CONFIG_UNKNOWN) {
                    assertEquals(2, actions.stream().filter("stage-config"::equals).count());
                    assertFalse(actions.contains("snapshot"));
                    assertFalse(actions.contains("publish"));
                    assertTrue(pendingCandidate(runtime, handle.id(), candidate.get().candidateId(),
                            "CANDIDATE_MAY_EXIST"));
                } else if (scenario == ScenarioType.PUBLISH_UNKNOWN) {
                    assertFalse(actions.contains("rollback"));
                    assertFalse(actions.contains("candidate-cleanup"));
                    assertTrue(pendingCandidate(runtime, handle.id(), candidate.get().candidateId(),
                            "PUBLISH_MAY_HAVE_RUN"));
                } else if (scenario == ScenarioType.ROLLBACK_UNKNOWN) {
                    assertTrue(actions.contains("rollback"));
                    assertFalse(actions.contains("candidate-cleanup"));
                    assertTrue(pendingCandidate(runtime, handle.id(), candidate.get().candidateId(),
                            "ROLLBACK_MAY_HAVE_RUN"));
                } else {
                    assertTrue(actions.contains("rollback"));
                    assertTrue(actions.contains("candidate-cleanup"));
                    assertTrue(pendingCandidate(runtime, handle.id(), candidate.get().candidateId(),
                            "REMOTE_CLEANUP_VERIFIED"));
                    assertEquals(1, countExecutionOutcome(runtime, "FAILED"));
                }
            }
            assertEquals(scenario == ScenarioType.SECOND_CONFIG_UNKNOWN ? 0 : 1,
                    actions.stream().filter("publish"::equals).count());
            assertEquals(1, actions.stream().filter("create-candidate"::equals).count());
        } finally {
            if (!interruptedHost)
                runtime.close();
        }
    }

    /** Saves an exact opaque purpose, optionally modeling a migrated unknown-purpose row. / 保存精确不透明用途，并可模拟迁移后用途未知的行。
     * @param context real original Mod / 真实原Mod
     * @param reference immutable revision / 不可变修订
     * @param unknownPurpose whether the original row lacks purpose / 原行是否缺失用途
     * @return original credential / 原凭据
     * @throws Exception if the real credential or metadata cannot be saved / 真实凭据或元数据无法保存时 */
    private SecretRef purposeSecret(ModContext context, DeploymentSecretReference reference, boolean unknownPurpose)
            throws Exception {
        var observed = new AtomicReference<SecretRef>();
        var setup = context.tasks()
                .submit(new TaskRequest("secret_purpose_fixture", "Secret purpose fixture", List.of(), true), task -> {
                    var secret = context.secrets().create("deployment.actual", new byte[]{7}, task);
                    observed.set(secret);
                    var reservation = context.secrets().reserve(secret, "secret:" + reference.identifier(), task);
                    new DeploymentSecretRepository(context.db()).saveRevision(task, new DeploymentSecretRevision(
                            reference, secret.id(), Instant.now(), Optional.of(secret.purpose())));
                    if (unknownPurpose)
                        try (var connection = context.db().openConnection(task);
                                var statement = connection.prepareStatement(
                                        "UPDATE secret_revision SET purpose=NULL WHERE secret_id=? AND secret_revision=?")) {
                            statement.setString(1, reference.identifier());
                            statement.setLong(2, reference.revision());
                            assertEquals(1, statement.executeUpdate());
                        }
                    context.secrets().resolve(reservation.id(), ReferenceResolution.REFERENCED, task);
                    return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                });
        assertEquals(Outcome.SUCCEEDED, setup.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
        return observed.get();
    }

    /** Verifies original v5 content identity is independent of review identity but sensitive to release inputs. / 验证原v5内容身份独立于审阅身份，且对发布输入敏感。
     * @param context real source capability / 真实来源能力
     * @param task original task / 原任务
     * @param request first reviewed candidate / 首个已审阅候选
     * @throws Exception if the second real frozen source cannot be inspected / 第二份真实冻结来源无法检查时 */
    private void assertReleaseIdentity(ModContext context, TaskContext task, ReviewedDeploymentRequest request)
            throws Exception {
        String original = DeploymentReviewedReleaseIdentity.from(request);
        var secondSelection = context.source().selectDirectory("Same source", task);
        try (var material = new DeploymentSourcePreparer(context.source()).prepare(secondSelection, 65536, 100, task);
                var archive = new DeploymentSourceArchiver(context.source()).prepare(material, 65536, task)) {
            assertNotEquals(request.material().snapshot().id(), material.snapshot().id());
            assertEquals(request.archive().sha256(), archive.sha256());
            var analysis = new DeploymentStandardAnalyzer().analyze(material, "service",
                    DeploymentProjectKind.GO_SERVICE, Optional.empty(), false, task);
            var review = new DeploymentReviewConsent(request.facts().applicationId(), request.server().id(),
                    material.snapshot().sha256(), archive.sha256(), request.review().confirmedAt().plusSeconds(10));
            var sameContent = new ReviewedDeploymentRequest(request.server(),
                    analysis.value().orElseThrow().assessment().facts().orElseThrow(), material, archive,
                    request.configuration(), request.secretReferences(), request.resources(), request.runtime(),
                    request.userAccessUrl(), request.limits(), review, false, true);
            assertEquals(original, DeploymentReviewedReleaseIdentity.from(sameContent));
        }
        var changedConfiguration = new ReviewedDeploymentRequest(request.server(), request.facts(), request.material(),
                request.archive(),
                DeploymentConfigurationSnapshot.create(request.facts().applicationId(), 2, "v1", Instant.now(),
                        List.of()),
                request.secretReferences(), request.resources(), request.runtime(), request.userAccessUrl(),
                request.limits(), request.review(), false, true);
        assertNotEquals(original, DeploymentReviewedReleaseIdentity.from(changedConfiguration));
        var firstSecret = new ReviewedDeploymentRequest(request.server(), request.facts(), request.material(),
                request.archive(), request.configuration(), List.of(new DeploymentSecretReference("db-password", 1)),
                request.resources(), request.runtime(), request.userAccessUrl(), request.limits(), request.review(),
                false, true);
        var secondSecret = new ReviewedDeploymentRequest(request.server(), request.facts(), request.material(),
                request.archive(), request.configuration(), List.of(new DeploymentSecretReference("db-password", 2)),
                request.resources(), request.runtime(), request.userAccessUrl(), request.limits(), request.review(),
                false, true);
        assertNotEquals(DeploymentReviewedReleaseIdentity.from(firstSecret),
                DeploymentReviewedReleaseIdentity.from(secondSecret));
        var requirement = ToolchainRequirement.declared(DeploymentToolchainType.GO, "1.24", "go.mod",
                ToolchainRequirement.PurposeType.BUILD);
        var version = DeploymentToolchainVersion.parse(DeploymentToolchainType.GO, "1.24.1").orElseThrow();
        var firstTool = new DeploymentResolvedToolchains.Selection(requirement, version,
                "/usr/local/lib/windowstolinux/toolchains/go-a", DeploymentResolvedToolchains.OriginType.MANAGED,
                "https://official.example/go", "a".repeat(64));
        var secondTool = new DeploymentResolvedToolchains.Selection(requirement, version,
                "/usr/local/lib/windowstolinux/toolchains/go-b", DeploymentResolvedToolchains.OriginType.MANAGED,
                "https://official.example/go", "b".repeat(64));
        assertNotEquals(
                DeploymentReviewedReleaseIdentity.bind(original,
                        new DeploymentResolvedToolchains("catalog", List.of(firstTool))),
                DeploymentReviewedReleaseIdentity.bind(original,
                        new DeploymentResolvedToolchains("catalog", List.of(secondTool))));
    }

    /** Reads the original checkpoint before fixture-only teardown. / 在仅供夹具使用的收尾前读取原检查点。
     * @param runtime actual host / 实际宿主
     * @param task original task / 原任务
     * @param candidate expected candidate identity / 预期候选身份
     * @param phase expected persisted phase / 预期持久阶段
     * @return whether the original record retains the candidate / 原记录是否保留候选
     * @throws Exception if durable inspection fails / 持久检查失败时 */
    private boolean pendingCandidate(PlatformRuntime runtime, UUID task, String candidate, String phase)
            throws Exception {
        try (var connection = runtime.persistence().database().openConnection();
                var query = connection.prepareStatement("SELECT recovery_json FROM platform_task_resource "
                        + "WHERE task_id=? AND resource_kind='CUSTOM' AND released_at IS NULL")) {
            query.setObject(1, task);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1).contains(candidate) && rows.getString(1).contains(phase);
            }
        }
    }

    /** Observes only the original author checkpoint release before injecting a formal commit interruption. / 只在注入正式提交中断前观察原作者检查点释放。
     * @param runtime actual host / 真实宿主
     * @param task original task / 原任务
     * @return true only if the original checkpoint was released / 仅原检查点已释放时为真
     * @throws SQLException if platform evidence cannot be read / 平台依据不可读取时 */
    private static boolean originalCheckpointReleased(PlatformRuntime runtime, UUID task) throws SQLException {
        try (var connection = runtime.persistence().database().openConnection();
                var query = connection.prepareStatement("SELECT released_at FROM platform_task_resource"
                        + " WHERE task_id=? AND resource_kind='CUSTOM' AND resource_id=?")) {
            query.setObject(1, task);
            query.setString(2, "gold.debug.windowstolinux.mod.deployment/checkpoint/deployment-publication-remote");
            try (var rows = query.executeQuery()) {
                return rows.next() && rows.getObject(1) != null;
            }
        }
    }

    /** Counts one original known business outcome while task cleanup remains unknown. / 在任务清理未决时计数一项原已知业务结果。
     * @param runtime actual host / 实际宿主
     * @param outcome original persisted outcome / 原持久结果
     * @return matching original rows / 匹配原行数
     * @throws Exception if schema access fails / Schema访问失败时 */
    private long countExecutionOutcome(PlatformRuntime runtime, String outcome) throws Exception {
        try (var access = scope(runtime, true);
                var connection = access.openConnection();
                var query = connection.prepareStatement("SELECT COUNT(*) FROM deployment_execution WHERE outcome=?")) {
            query.setString(1, outcome);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    /** Counts one authoritative Mod business table through its actual schema scope. / 经实际Schema作用域计数一张权威Mod业务表。
     * @param runtime actual host / 实际宿主
     * @param table fixed test table / 固定测试表
     * @return actual rows / 实际行数
     * @throws Exception if access fails / 访问失败时 */
    private long countMod(PlatformRuntime runtime, String table) throws Exception {
        assertEquals("deployment_release", table);
        try (var access = scope(runtime, true);
                var connection = access.openConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_release")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    /** Counts the actual business secret binding rather than relying on the reviewed request. / 计数真实业务秘密绑定而非只依赖审阅请求。
     * @param runtime actual host / 真实宿主
     * @return saved binding count / 已存绑定数
     * @throws Exception if original data cannot be read / 无法读取原数据时 */
    private long countReleaseSecrets(PlatformRuntime runtime) throws Exception {
        try (var access = scope(runtime, true);
                var connection = access.openConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_unit_release_secret_reference")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    /** Replaces only the SSH peer while retaining real task, source, database and interaction capabilities. / 只替换SSH对等端，保留真实任务、来源、数据库和交互能力。
     * @param real original Mod context / 原Mod上下文
     * @param session controlled original session / 受控原会话
     * @return delegated context / 委托上下文 */
    private ModContext withControlledSession(ModContext real, RemoteSession session, PlatformRuntime runtime,
            ScenarioType scenario) {
        var commitRefused = new AtomicBoolean();
        LinuxService linux = (LinuxService) Proxy.newProxyInstance(LinuxService.class.getClassLoader(),
                new Class<?>[]{LinuxService.class}, (proxy, method, args) -> {
                    assertEquals("connect", method.getName());
                    assertEquals("server-publication", ((ServerRef) args[0]).id());
                    return session;
                });
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("linux"))
                        return linux;
                    if (method.getName().equals("db") && scenario == ScenarioType.COMMIT_WINDOW)
                        return new ModDatabase() {
                            public gold.debug.windowstolinux.appapi.db.SchemaIdentity identity() throws ApiException {
                                return real.db().identity();
                            }

                            public java.sql.Connection openConnection(TaskContext task)
                                    throws ApiException, SQLException {
                                if (originalCheckpointReleased(runtime, task.id())
                                        && commitRefused.compareAndSet(false, true))
                                    throw new SQLException(
                                            "Controlled interruption before formal business transaction");
                                return real.db().openConnection(task);
                            }
                        };
                    if (method.getName().equals("interaction"))
                        return new InteractionService() {
                            public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext task)
                                    throws ApiException {
                                var approved = real.interaction().approve(intent, task);
                                approved.value().ifPresent(value -> grants.put(value, intent));
                                return approved;
                            }

                            public OperationResult<String> requestText(String title, String prompt, boolean secret,
                                    TaskContext task) throws ApiException {
                                return real.interaction().requestText(title, prompt, secret, task);
                            }
                        };
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /** Supplies exact candidate and release responses without a real SSH endpoint. / 不使用真实SSH端点而提供精确候选及发布回执。
     * @param scenario requested remote outcome / 所需远端结果
     * @param actions ordered actual calls / 实际调用顺序
     * @param sha original archive SHA / 原归档摘要
     * @param bytes original archive byte count / 原归档字节数
     * @param path original archive path / 原归档路径
     * @param candidate original candidate / 原候选
     * @param callbackEnded remote callback completion / 远端回调完成
     * @return borrowed controlled session / 借用受控会话 */
    private RemoteSession peer(ScenarioType scenario, List<String> actions, AtomicReference<String> sha,
            AtomicLong bytes, AtomicReference<Path> path, AtomicReference<DeploymentRemoteWorkspace> candidate,
            CountDownLatch callbackEnded) {
        var configStages = new AtomicInteger();
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                    if (method.getName().equals("facts"))
                        return new RemoteFacts("", "", "SHA256:fixture-host");
                    if (method.getName().equals("close")) {
                        callbackEnded.countDown();
                        if (scenario == ScenarioType.HEALTH_FAILED_ROLLBACK_CLOSE_UNKNOWN)
                            throw new IllegalStateException("Controlled original session close acknowledgement lost");
                        return null;
                    }
                    assertTrue(grants.containsKey(args[1]), "Original host grant must be passed unchanged");
                    if (method.getName().equals("upload")) {
                        actions.add("upload");
                        var transfer = (TransferRequest) args[0];
                        assertEquals(path.get(), transfer.localPath());
                        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(new TransferResult(bytes.get(),
                                sha.get(), transfer.localPath(), transfer.remoteAbsolutePath())), Optional.empty());
                    }
                    assertEquals("execute", method.getName());
                    String script = ((CommandRequest) args[0]).script();
                    if (script.contains("managed-helper' probe")) {
                        actions.add("helper-probe");
                        return reply("HELPER=1\nPROTOCOL=11\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256
                                + "\n");
                    }
                    if (script.contains("DISTRO_ID=")) {
                        actions.add("host-facts");
                        return reply(
                                "DISTRO_ID=ubuntu\nVERSION=24.04\nARCH=x86_64\nPACKAGE_MANAGER=apt\nPACKAGE_ARCH=amd64\nSYSTEMD=1\nCPU_LEVEL=x86-64-v3\nSECURITY_MODULE=apparmor\nSECURITY_STATE=enabled\nFIREWALL=ufw\nFIREWALL_STATE=active\n");
                    }
                    if (script.contains("stat -f -c")) {
                        actions.add("capacity");
                        return reply("1000000000 4096\n");
                    }
                    if (script.contains("build-preflight")) {
                        actions.add("build-preflight");
                        return reply("");
                    }
                    if (script.contains("prepare-toolchains 'prepare'")) {
                        actions.add("prepare-tools");
                        return reply("TOOLCHAIN=GO|1.24.1|/usr/local/lib/windowstolinux/toolchains/versions/go-"
                                + "b".repeat(64) + "|MANAGED|https://official.example/go|" + "b".repeat(64) + "\n");
                    }
                    if (script.contains("prepare-toolchains 'bind'")) {
                        actions.add("bind-tools");
                        String encoded = script.substring(script.indexOf("'bind' '") + 8);
                        encoded = encoded.substring(0, encoded.indexOf('\''));
                        var tools = DeploymentToolchainBindingCodec.decode(new String(
                                Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8));
                        return reply("TOOLCHAIN_BINDING=" + DeploymentToolchainBindingCodec.identity(tools) + "\n");
                    }
                    if (script.contains("candidate-create-task")) {
                        actions.add("create-candidate");
                        return reply("CANDIDATE=" + candidate.get().candidateRoot() + "\n");
                    }
                    if (script.contains("stat -c '%s'")) {
                        actions.add("verify-archive");
                        return reply("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\nBYTES=" + bytes.get() + "\nSHA256="
                                + sha.get() + "\nOWNER=0\n");
                    }
                    if (script.contains("candidate-query-task")) {
                        actions.add("query-candidate");
                        return reply("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\n");
                    }
                    if (script.startsWith("exec /usr/bin/python3 -I -c ")) {
                        actions.add("build");
                        return reply("WTL_BUILD_REPORT=1\nEXIT=0\nBYTES=0\nSHA256=" + "c".repeat(64)
                                + "\nUID=1001\nENGINE=ordinary\nTOOL=GO_MODULE\nCOMPAT=0\nLIMIT=-\nCONFLICT=0\nHEAD=\nTAIL=\n");
                    }
                    if (script.contains("stage-config")) {
                        actions.add("stage-config");
                        return scenario == ScenarioType.SECOND_CONFIG_UNKNOWN && configStages.incrementAndGet() == 2
                                ? unknown()
                                : reply("INPUT_STAGED=1\n");
                    }
                    if (script.contains("stage-secret")) {
                        actions.add("stage-secret");
                        return reply("INPUT_STAGED=1\n");
                    }
                    if (script.contains("snapshot-deployment")) {
                        actions.add("snapshot");
                        var taskToken = java.util.regex.Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                                .matcher(script);
                        assertTrue(taskToken.find(), script);
                        return reply(scenario == ScenarioType.HEALTH_FAILED_ROLLBACK_OLD_MODE
                                ? "SNAPSHOT_TOKEN=" + taskToken.group()
                                        + "\nPREVIOUS=1\nPREVIOUS_RUNNING=0\nPREVIOUS_DIGEST=" + "d".repeat(64)
                                        + "\nPREVIOUS_KIND=deployment\n"
                                : "PREVIOUS=0\n");
                    }
                    if (script.contains("publish-deployment")) {
                        actions.add("publish");
                        if (scenario == ScenarioType.PUBLISH_UNKNOWN || scenario == ScenarioType.ROLLBACK_UNKNOWN
                                || scenario == ScenarioType.RETAIN_UNKNOWN
                                || scenario == ScenarioType.HEALTH_FAILED_ROLLBACK_CLOSE_UNKNOWN
                                || scenario == ScenarioType.COMMIT_WINDOW) {
                            var match = java.util.regex.Pattern
                                    .compile("'publish-deployment' 'service' '[a-z0-9-]+' '([0-9a-f]{64})'")
                                    .matcher(script);
                            assertTrue(match.find(), script);
                            Files.writeString(root.resolve("controlled-remote-publication"),
                                    match.group(1) + "\n" + candidate.get().candidateId() + "\n");
                        }
                        return scenario == ScenarioType.PUBLISH_UNKNOWN ? unknown() : reply("PUBLISHED=1\n");
                    }
                    if (script.contains("application-health")) {
                        actions.add("health");
                        return scenario == ScenarioType.HEALTH_FAILED_ROLLBACK
                                || scenario == ScenarioType.HEALTH_FAILED_ROLLBACK_OLD_MODE
                                || scenario == ScenarioType.HEALTH_FAILED_ROLLBACK_CLOSE_UNKNOWN
                                || scenario == ScenarioType.ROLLBACK_UNKNOWN
                                        ? reply(1, "HEALTHY=0\n")
                                        : reply("HEALTHY=1\n");
                    }
                    if (script.contains("inspect-runtime")) {
                        actions.add("inspect-runtime");
                        return reply(scenario == ScenarioType.HEALTH_FAILED_ROLLBACK_OLD_MODE
                                ? "KIND=ordinary\n"
                                : "KIND=deployment\nMODE=DAEMON\n");
                    }
                    if (script.contains("systemctl show --property=ActiveState")) {
                        actions.add("observe-old-ordinary");
                        return reply("ActiveState=inactive\nSubState=dead\nResult=success\nExecMainCode=0\n"
                                + "ExecMainStatus=0\nMainPID=0\n");
                    }
                    if (script.contains("observe-deployment")) {
                        actions.add("observe");
                        return reply(
                                "OWNER=1\nRUNNING=1\nENABLED=enabled\nQUERY_OK=1\nActiveState=active\nSubState=running\nResult=success\nExecMainCode=0\nExecMainStatus=0\nMainPID=123\n");
                    }
                    if (script.contains("rollback-deployment")) {
                        actions.add("rollback");
                        return scenario == ScenarioType.ROLLBACK_UNKNOWN ? unknown() : reply("ROLLED_BACK=1\n");
                    }
                    if (script.contains("application-maintenance")) {
                        actions.add("maintenance-end");
                        return reply("MAINTENANCE=end\n");
                    }
                    if (script.contains("candidate-cleanup-task")) {
                        actions.add("candidate-cleanup");
                        return reply("CANDIDATE_CLEANED=1\n");
                    }
                    if (script.contains("'retain'")) {
                        actions.add("retain");
                        return scenario == ScenarioType.RETAIN_UNKNOWN ? unknown() : reply("RETAINED=1\n");
                    }
                    throw new AssertionError(script);
                });
    }

    /** Returns an exact successful command envelope. / 返回精确成功命令封套。
     * @param output complete stdout / 完整标准输出
     * @return known result / 已知结果 */
    private static OperationResult<CommandResult> reply(String output) {
        return reply(0, output);
    }

    /** Returns an exact known process exit. / 返回精确已知进程退出。
     * @param exit observed exit / 已观测退出
     * @param output complete stdout / 完整标准输出
     * @return known result / 已知结果 */
    private static OperationResult<CommandResult> reply(int exit, String output) {
        return new OperationResult<>(Outcome.SUCCEEDED,
                Optional.of(new CommandResult(Optional.of(exit), output, "", false)), Optional.empty());
    }

    /** Loses the original remote acknowledgement without inventing a retry. / 丢失原远端回执而不补造重试。
     * @return uncertain result / 未知结果 */
    private static OperationResult<CommandResult> unknown() {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                "FIXTURE_PUBLICATION_ACK_LOST", "Controlled receipt was lost", Map.of(), Optional.empty())));
    }
}
