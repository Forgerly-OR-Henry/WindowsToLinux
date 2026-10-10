package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.model.AiUsage;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteFacts;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflict;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferProgress;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferResult;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStall;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStallChoice;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.ref.ResourceRefResult;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentConfigStore;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentGraphStore;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentReleaseRecord;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentReleaseStore;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSecretStore;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.file.git.*;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentNativeDatabaseWire;
import gold.debug.windowstolinux.mod.deployment.secret.DeploymentSecretProvisionFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigScopeType;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentDatabasePlanActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentNativeDatabaseActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentNativeDatabaseSetup;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentProtocolBootstrapActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticWorkflow;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.plan.DeploymentReviewedReleaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Executes the real original publication task, source handles, H2 and host review against a controlled peer. / 用受控对等端执行真实原发布任务、来源句柄、H2和宿主审核。 */
class DeploymentStandardPublishRunnerTest extends DeploymentRuntimeFixture {
    /** Declared wire identity of this controlled peer; actual loopback recovery captures its own key. / 此受控对等端声明的 wire 身份；实际回环恢复捕获自身密钥。 */
    private static final String CONTROLLED_FINGERPRINT = "SHA256:"
        + Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);

    private static final Path GIT = Arrays.stream(System.getenv("PATH").split(java.io.File.pathSeparator))
        .map(path -> Path.of(path).resolve("git.exe")).filter(Files::isRegularFile).findFirst().orElseThrow()
        .toAbsolutePath().normalize();

    /** Three distinct original remote outcomes. / 三种不同的原远端结果。 */
    private enum ScenarioType {
        SUCCESS, SECOND_CONFIG_UNKNOWN, PUBLISH_UNKNOWN, HEALTH_FAILED_ROLLBACK, HEALTH_FAILED_ROLLBACK_UNSUPPORTED_MODE, HEALTH_FAILED_ROLLBACK_CLOSE_UNKNOWN, ROLLBACK_UNKNOWN, RETAIN_UNKNOWN, COMMIT_WINDOW, PURPOSE_PRESENT, PURPOSE_UNKNOWN, PURPOSE_MISMATCH
    }

    /** Actual host-reviewed grants. / 宿主实际审核的授权。 */
    private final Map<ApprovalGrant, ActionIntent> grants = new ConcurrentHashMap<>();

    /** Optional controlled loopback port for a later cross-process recovery probe. / 后续跨进程恢复探针可用的受控回环端口。 */
    private int recoveryPort = 22;

    /** Whether this fixture selects the actual optional Git provider. / 此夹具是否选择实际可选 Git 提供方。 */
    private boolean gitScenario;

    @Override
    protected boolean gitEnabled() {
        return gitScenario;
    }

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
            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerId server,
                                                           TaskSnapshot task, CancelToken cancellation) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerId server, TaskSnapshot task,
                                                       CancelToken cancellation) {
                throw new AssertionError();
            }

            public String transferProgress(TransferProgress progress) {
                return "Controlled upload";
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation) {
                assertEquals(List.of("server-publication"), intent.serverIds());
                return true;
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                               CancelToken cancellation) {
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
    @EnumSource(value = ScenarioType.class, names = {"SUCCESS", "HEALTH_FAILED_ROLLBACK"})
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

    /** Runs the new page workflow through actual source, reviewed plan and original publication under one task. / 在同一任务内通过真实来源、已审阅计划与原发布运行新页面流程。
     * @throws Exception if original source or publication fails / 原来源或发布失败时 */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void staticWorkflowPublishesOneOriginalTaskWithReviewedInputs(boolean git) throws Exception {
        runStaticWorkflow(git, false);
    }

    /** Publishes source-derived SQLite with separate schema consent and the exact managed resource path. / 经独立结构同意后发布源码派生 SQLite，并核对精确受管资源路径。
     * @throws Exception if original publication fails / 原发布失败时 */
    @Test
    void staticWorkflowPublishesSqliteWithSourceBoundFirstInitialization() throws Exception {
        runStaticWorkflow(false, true);
    }

    /** Installs a missing fixed helper under the reviewed page task before ordinary publication. / 普通发布前在页面已审阅原任务下安装缺失的固定 helper。
     * @throws Exception if original preparation or publication fails / 原准备或发布失败时 */
    @Test
    void staticWorkflowBootstrapsMissingHelperInOriginalPublicationTask() throws Exception {
        runStaticWorkflow(false, false, true);
    }

    /** Publishes an actual source-bound native target twice using its one immutable original-task credential. / 用原任务同一不可变凭据两次发布真实源码绑定原生目标。
     * @throws Exception if the controlled helper or original CLASS/H2 publication fails / 受控 helper 或原 CLASS/H2 发布失败时 */
    @Test
    void staticWorkflowPublishesNativeDatabaseAndReusesExactSecretRevision() throws Exception {
        runStaticWorkflow(false, false, false, true);
    }

    /** Retries an existing target's rejected password under the same child operation and saves only one revision. / 在同一子操作下重试已有目标被拒密码且只保存一版修订。
     * @throws Exception if the original task or controlled helper fails / 原任务或受控 helper 失败时 */
    @Test
    void staticWorkflowRetriesExistingNativeAuthenticationBeforeSavingOnce() throws Exception {
        runStaticWorkflow(false, false, false, true, true);
    }

    /** A stored rejected revision remains immutable while two corrected attempts save only revision two. / 已存修订被拒时保持不可变，两次更正仅保存第二版。
     * @throws Exception if original task or protected storage fails / 原任务或受保护存储失败时 */
    @Test
    void staticWorkflowRotatesStoredNativeRevisionOnlyAfterSuccessfulRetry() throws Exception {
        runStaticWorkflow(false, false, false, true, false, true);
    }

    /** Installs a reviewed absent native package before the original publication. / 原发布前安装已审阅的缺失原生软件包。
     * @throws Exception if original installation or publication fails / 原安装或发布失败时 */
    @Test
    void staticWorkflowInstallsMissingNativeDatabase() throws Exception {
        runStaticWorkflow(false, false, false, true, false, false, NativeInstallType.FIRST);
    }

    /** Replaces an incompatible package only with backup, downtime and manual restore confirmation. / 仅经备份停机及人工恢复确认替换不兼容包。
     * @throws Exception if original replacement or publication fails / 原替换或发布失败时 */
    @Test
    void staticWorkflowReplacesNativePackageAfterManualRestoration() throws Exception {
        runStaticWorkflow(false, false, false, true, false, false, NativeInstallType.REPLACE);
    }

    /** Resumes a previously interrupted replacement through its original manual restore gate. / 经原人工恢复门槛继续此前中断的替换。
     * @throws Exception if original restoration or publication fails / 原恢复或发布失败时 */
    @Test
    void staticWorkflowRecoversPendingNativeReplacement() throws Exception {
        runStaticWorkflow(false, false, false, true, false, false, NativeInstallType.PENDING);
    }

    /** Reapproves existing-schema SQL in the original task. / 在原任务重新批准已有结构 SQL。
     * @throws Exception if original execution fails / 原执行失败时 */
    @Test
    void staticWorkflowRetriesExistingSchemaOnlyAfterFreshIndependentApproval() throws Exception {
        runStaticWorkflow(false, false, false, true, true, false, NativeInstallType.EXISTING_SCHEMA_APPROVED);
    }

    /** Refusal prevents initialization and publication. / 拒绝阻止初始化和发布。
     * @throws Exception if original execution fails / 原执行失败时 */
    @Test
    void staticWorkflowDeclinesExistingSchemaWithoutInitializationOrPublication() throws Exception {
        runStaticWorkflow(false, false, false, true, true, false, NativeInstallType.EXISTING_SCHEMA_REFUSED);
    }

    /** Controlled native package scenarios. / 受控原生软件包场景。 */
    @Test
    void nodeWithoutEndpointPublishesProcessHealthAndPersistsOriginalRuntimeWithoutLeakedResources() throws Exception {
        runStaticWorkflow(false, false, false, false, false, false, NativeInstallType.NO_PORT_NODE);
    }

    @Test
    void nativeAdministratorAndApplicationRetriesRequireRenewedSchemaConsentAndClearOwnedInputs() throws Exception {
        runStaticWorkflow(false, false, false, true, true, false, NativeInstallType.AUTH_SCHEMA_APPROVED);
    }

    @Test
    void fullControlCannotOverrideIndependentNativeSchemaRefusalAfterAuthenticationRetries() throws Exception {
        runStaticWorkflow(false, false, false, true, true, false, NativeInstallType.AUTH_SCHEMA_FULL_CONTROL_REFUSED);
    }

    private enum NativeInstallType {
        /** An existing compatible instance. / 既有兼容实例。 */
        NONE,
        /** An absent instance requiring reviewed installation. / 缺失实例需经审阅安装。 */
        FIRST,
        /** An incompatible instance requiring manual restoration. / 不兼容实例需人工恢复。 */
        REPLACE,
        /** A previous installation left manual restoration pending. / 此前安装留下待人工恢复状态。 */
        PENDING,
        /** The original start reply disappears after dispatch. / 原启动发送后回包丢失。 */
        START_UNKNOWN,
        /** Independently approved existing schema. / 独立批准已有结构。 */
        EXISTING_SCHEMA_APPROVED,
        /** Independently refused existing schema. / 独立拒绝已有结构。 */
        EXISTING_SCHEMA_REFUSED, AUTH_SCHEMA_APPROVED, AUTH_SCHEMA_FULL_CONTROL_REFUSED, NO_PORT_NODE
    }

    /** Shares the original CLASS/H2 publication fixture across local, Git and SQLite sources. / 为本地、Git 和 SQLite 来源复用原 CLASS/H2 发布夹具。
     * @param git whether real local Git is used / 是否使用真实本机 Git
     * @param sqlite whether source declares SQLite / 源码是否声明 SQLite
     * @throws Exception if original publication fails / 原发布失败时 */
    private void runStaticWorkflow(boolean git, boolean sqlite) throws Exception {
        runStaticWorkflow(git, sqlite, false);
    }

    /** Runs the same publication with optional first-server helper installation. / 使用可选首次服务器 helper 安装运行同一发布。
     * @param git whether Git is selected / 是否选择 Git
     * @param sqlite whether SQLite is declared / 是否声明 SQLite
     * @param installHelper whether the first publication finds no fixed helper / 首次发布是否缺少固定 helper
     * @throws Exception if the original task fails / 原任务失败时 */
    private void runStaticWorkflow(boolean git, boolean sqlite, boolean installHelper) throws Exception {
        runStaticWorkflow(git, sqlite, installHelper, false);
    }

    /** Runs an optional native source declaration through the same original publication. / 在同一原发布中运行可选原生来源声明。
     * @param git whether Git is selected / 是否选择 Git
     * @param sqlite whether SQLite is declared / 是否声明 SQLite
     * @param installHelper whether helper is initially absent / helper 是否初始缺失
     * @param nativeDatabase whether PostgreSQL is declared / 是否声明 PostgreSQL
     * @throws Exception if the original task fails / 原任务失败时 */
    private void runStaticWorkflow(boolean git, boolean sqlite, boolean installHelper, boolean nativeDatabase)
        throws Exception {
        runStaticWorkflow(git, sqlite, installHelper, nativeDatabase, false);
    }

    /** Runs an optional existing-target authentication retry through the same real publication. / 通过同一真实发布运行可选已有目标认证重试。
     * @param git whether Git is selected / 是否选择 Git
     * @param sqlite whether SQLite is declared / 是否声明 SQLite
     * @param installHelper whether helper is absent / helper 是否缺失
     * @param nativeDatabase whether native DB is declared / 是否声明原生数据库
     * @param authRetry whether first existing-target password is refused / 首次已有目标密码是否被拒
     * @throws Exception if original publication fails / 原发布失败时 */
    private void runStaticWorkflow(boolean git, boolean sqlite, boolean installHelper, boolean nativeDatabase,
                                   boolean authRetry) throws Exception {
        runStaticWorkflow(git, sqlite, installHelper, nativeDatabase, authRetry, false);
    }

    /** Runs the same real source with optional stored-credential rotation after first publication. / 首次发布后使用可选已存凭据轮换运行同一真实来源。
     * @param git whether Git is selected / 是否选择 Git
     * @param sqlite whether SQLite is declared / 是否声明 SQLite
     * @param installHelper whether helper is absent / helper 是否缺失
     * @param nativeDatabase whether native DB is declared / 是否声明原生数据库
     * @param authRetry whether initial existing target rejects input / 初始已有目标是否拒绝输入
     * @param rotateStored whether second publication rejects old revision / 第二次发布是否拒绝旧修订
     * @throws Exception if original publication fails / 原发布失败时 */
    private void runStaticWorkflow(boolean git, boolean sqlite, boolean installHelper, boolean nativeDatabase,
                                   boolean authRetry, boolean rotateStored) throws Exception {
        runStaticWorkflow(git, sqlite, installHelper, nativeDatabase, authRetry, rotateStored, NativeInstallType.NONE);
    }

    /** Runs one exact native package scenario under the same original publication. / 在同一原发布下运行精确原生软件包场景。
     * @param git whether Git is selected / 是否选择 Git
     * @param sqlite whether SQLite is declared / 是否声明 SQLite
     * @param installHelper whether helper is absent / helper 是否缺失
     * @param nativeDatabase whether native DB is declared / 是否声明原生数据库
     * @param authRetry whether initial target authentication fails / 初次目标认证是否失败
     * @param rotateStored whether saved password rotates / 已存密码是否轮换
     * @param nativeInstall controlled package scenario / 受控软件包场景
     * @throws Exception if original publication fails / 原发布失败时 */
    private void runStaticWorkflow(boolean git, boolean sqlite, boolean installHelper, boolean nativeDatabase,
                                   boolean authRetry, boolean rotateStored, NativeInstallType nativeInstall) throws Exception {
        gitScenario = git;
        boolean noPortNode = nativeInstall == NativeInstallType.NO_PORT_NODE;
        Files.createDirectories(selectedSourceDirectory());
        if (noPortNode) {
            Files.writeString(selectedSourceDirectory().resolve("package.json"),
                "{\"name\":\"service\",\"version\":\"1.0.0\",\"engines\":{\"node\":\"22\"},"
                    + "\"scripts\":{\"build\":\"node build.js\",\"start\":\"node worker.js\"}}");
            Files.writeString(selectedSourceDirectory().resolve("package-lock.json"),
                "{\"name\":\"service\",\"version\":\"1.0.0\",\"lockfileVersion\":3,\"packages\":{\"\":{\"name\":\"service\",\"version\":\"1.0.0\"}}}");
            Files.writeString(selectedSourceDirectory().resolve("build.js"), "console.log('build complete');\n");
            Files.writeString(selectedSourceDirectory().resolve("worker.js"), "setInterval(() => {}, 1000);\n");
        } else {
            Files.writeString(selectedSourceDirectory().resolve("go.mod"), "module example.test/service\n\ngo 1.24\n");
            Files.writeString(selectedSourceDirectory().resolve("go.sum"), "example.test/dependency v1 h1:fixture\n");
            Files.writeString(selectedSourceDirectory().resolve("main.go"), "package main\nfunc main() {}\n");
        }
        if (sqlite) {
            Files.writeString(selectedSourceDirectory().resolve("windowstolinux-db.properties"),
                "db=main\ndb.main.engine=SQLITE\ndb.main.path=DEFAULT\ndb.main.pathEnvironment=DB_PATH\n"
                    + "db.main.initialize=schema.sql\ndb.main.seed=seed.db\n");
            Files.writeString(selectedSourceDirectory().resolve("schema.sql"), "CREATE TABLE sample(id integer);\n");
            Files.write(selectedSourceDirectory().resolve("seed.db"), new byte[]{1, 2, 3});
        }
        if (nativeDatabase) {
            Files.writeString(selectedSourceDirectory().resolve("windowstolinux-db.properties"),
                "db=main\ndb.main.engine=POSTGRESQL\ndb.main.version=>=16\n"
                    + "db.main.database=sampledb\ndb.main.username=sampleuser\n"
                    + "db.main.environmentPrefix=DB\ndb.main.passwordEnvironment=DB_PASSWORD\n"
                    + "db.main.initialize=schema.sql\n");
            Files.writeString(selectedSourceDirectory().resolve("schema.sql"), "CREATE TABLE sample(id integer);\n");
        }
        if (git) {
            git(selectedSourceDirectory(), "init", "-b", "main");
            git(selectedSourceDirectory(), "config", "user.email", "test@example.invalid");
            git(selectedSourceDirectory(), "config", "user.name", "Test");
        }
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = new ServerId("server-publication");
            runtime.servers().saveServerProfile(
                ServerProfile.fromStored(
                    new StoredServerProfile(server.id(), "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var original = runtime.modContexts().get(OWNER).context();
            var secretRevision = new DeploymentSecretRef("page-application", 1);
            byte[] plaintext = new byte[]{(byte) 0xe4, (byte) 0xbd, (byte) 0xa0, (byte) 0xe5, (byte) 0xa5, (byte) 0xbd,
                0x21};
            byte[] expectedPlaintext = plaintext.clone();
            var provision = new DeploymentSecretProvisionFlow(original).submitOwned(server, secretRevision,
                "deployment-application", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                task -> plaintext);
            var saved = provision.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, saved.outcome(), saved.toString());
            assertArrayEquals(new byte[plaintext.length], plaintext, "Page-owned plaintext must be cleared");
            var stored = saved.value().orElseThrow();
            assertEquals(Optional.of("deployment-application"), stored.purpose());
            var inspectSecret = original.tasks().submit(
                new TaskRequest("page-secret-inspect", "Inspect secret", List.of(server.id()), false), task -> {
                    var actual = new DeploymentSecretStore(original.db()).findRevision(task, secretRevision);
                    assertEquals(Optional.of(stored), actual);
                    boolean matched = original.secrets().use(
                        new SecretId(stored.credentialId(), stored.purpose().orElseThrow()),
                        stored.purpose().orElseThrow(), task, bytes -> Arrays.equals(expectedPlaintext, bytes));
                    assertTrue(matched, "Protected platform value must round-trip without entering Mod metadata");
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                });
            assertEquals(TaskResult.SUCCEEDED,
                inspectSecret.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            Arrays.fill(expectedPlaintext, (byte) 0);
            List<String> firstSnapshot = null;
            String firstOwner = null;
            String firstApplication = null;
            var firstNativeSecret = new AtomicReference<DeploymentSecretRevision>();
            var firstNativePasswordDigest = new AtomicReference<String>();
            var nativeSecretAudit = new AtomicReference<String>();
            var nativeState = new NativeState();
            nativeState.install = nativeInstall;
            nativeState.installed = nativeInstall != NativeInstallType.FIRST;
            nativeState.restorePending = nativeInstall == NativeInstallType.PENDING;
            nativeState.prepared = authRetry;
            nativeState.authFailuresRemaining = authRetry ? 1 : 0;
            var passwordPrompts = new AtomicInteger();
            var ownedInputs = new ArrayList<byte[]>();
            boolean chainedAuthentication = nativeInstall == NativeInstallType.AUTH_SCHEMA_APPROVED
                || nativeInstall == NativeInstallType.AUTH_SCHEMA_FULL_CONTROL_REFUSED;
            boolean fullControl = nativeInstall == NativeInstallType.AUTH_SCHEMA_FULL_CONTROL_REFUSED;
            for (int publication = 1; publication <= (noPortNode ? 1 : 2); publication++) {
                final int currentPublication = publication;
                boolean schemaReview = nativeInstall == NativeInstallType.EXISTING_SCHEMA_APPROVED
                    || nativeInstall == NativeInstallType.EXISTING_SCHEMA_REFUSED || chainedAuthentication;
                if (chainedAuthentication)
                    nativeState.adminFailuresRemaining = 1;
                if (schemaReview) {
                    nativeState.schemaFailureRemaining = 1;
                    nativeState.initialized = false;
                }
                if (!nativeDatabase && !noPortNode)
                    Files.writeString(selectedSourceDirectory().resolve("main.go"),
                        "package main\nfunc main() { /* publication " + publication + " */ }\n");
                if (rotateStored && publication == 2) {
                    nativeState.authFailuresRemaining = 2;
                    nativeState.acceptRotation = true;
                }
                if (git) {
                    git(selectedSourceDirectory(), "add", ".");
                    git(selectedSourceDirectory(), "commit", "-m", "publication-" + publication);
                }
                final String commit = git ? git(selectedSourceDirectory(), "rev-parse", "HEAD") : null;
                final int expectedNativeRevision = rotateStored && publication == 2 ? 2 : 1;
                var actions = new CopyOnWriteArrayList<String>();
                var sha = new AtomicReference<String>();
                var manifestSha = new AtomicReference<String>();
                var bytes = new AtomicLong();
                var path = new AtomicReference<Path>();
                var candidate = new AtomicReference<DeploymentRemoteWorkspace>();
                var callbackEnded = new CountDownLatch(1);
                var reviewedTask = new AtomicReference<TaskContext>();
                var helperInstalls = new AtomicInteger();
                var existing = peer(ScenarioType.SUCCESS, actions, sha, bytes, path, candidate, callbackEnded,
                    noPortNode);
                var session = nativeDatabase
                    ? nativePeer(existing, original, reviewedTask, nativeState, actions, grants)
                    : installHelper && publication == 1
                    ? missingHelper(existing, original, reviewedTask, helperInstalls)
                    : existing;
                var remoteBound = withControlledSession(original, session, runtime, ScenarioType.SUCCESS);
                var independentReviews = new AtomicInteger();
                var bound = fullControl
                    ? withFullControlModels(remoteBound, reviewedTask, independentReviews)
                    : remoteBound;
                var workflow = new DeploymentStaticWorkflow(bound, new DeploymentRefFlow(bound));
                var observedTask = new AtomicReference<UUID>();
                var reviewedApplication = new AtomicReference<String>();
                var reviewer = new DeploymentStaticWorkflow.Reviewer() {
                    public OperationResult<Map<String, String>> databaseInputs(ServerId exact, String componentId,
                                                                               String manifest, Path frozenRoot, String applicationId,
                                                                               gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.db.DatabaseProjectChecker.Report assessment,
                                                                               TaskContext task) throws Exception {
                        assertTrue(sqlite || nativeDatabase);
                        assertEquals(observedTask.get(), task.id());
                        assertEquals(1, assessment.databases().size());
                        if (sqlite)
                            assertEquals("seed.db",
                                assessment.databases().getFirst().sqlite().orElseThrow().seedFile());
                        if (nativeDatabase)
                            nativeState.applicationId = applicationId;
                        if (nativeDatabase)
                            nativeState.sourceDigest = DeploymentNativeDatabaseSetup
                                .local(componentId, applicationId, manifest, frozenRoot, assessment, task)
                                .sourceDigest();
                        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(Map.of()), Optional.empty());
                    }

                    public OperationResult<Boolean> confirmSqliteInitialization(ServerId exact, String componentId,
                                                                                String manifest,
                                                                                gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.db.DatabaseProjectChecker.Report assessment,
                                                                                TaskContext task) {
                        assertTrue(sqlite);
                        assertEquals(observedTask.get(), task.id());
                        assertEquals(List.of("schema.sql"), assessment.databases().getFirst().initializationFiles());
                        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(true), Optional.empty());
                    }

                    @Override
                    public OperationResult<byte[]> administratorPassword(ServerId exact,
                                                                         gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.Instance instance,
                                                                         TaskContext task) {
                        assertTrue(chainedAuthentication);
                        assertEquals(server, exact);
                        assertSame(reviewedTask.get(), task);
                        assertEquals("postgresql-16", instance.id());
                        assertEquals(0, nativeState.adminFailuresRemaining);
                        byte[] owned = "fresh-administrator".getBytes(StandardCharsets.UTF_8);
                        ownedInputs.add(owned);
                        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(owned), Optional.empty());
                    }

                    @Override
                    public OperationResult<byte[]> applicationPassword(ServerId exact,
                                                                       gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DatabaseRequirement database,
                                                                       TaskContext task) {
                        if (!authRetry && !rotateStored)
                            throw new AssertionError("A new owned target must use its generated and saved password");
                        int prompt = passwordPrompts.incrementAndGet();
                        assertSame(reviewedTask.get(), task);
                        byte[] owned = (prompt == 1
                            ? "incorrect"
                            : rotateStored && currentPublication == 2 ? "rotated-password" : "correct-password")
                            .getBytes(StandardCharsets.UTF_8);
                        ownedInputs.add(owned);
                        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(owned), Optional.empty());
                    }

                    @Override
                    public OperationResult<Boolean> install(ServerId exact,
                                                            gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DatabaseRequirement database,
                                                            gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.Candidate candidate,
                                                            TaskContext task) {
                        assertEquals(NativeInstallType.FIRST, nativeInstall);
                        assertEquals("postgresql-16", candidate.name());
                        assertEquals(observedTask.get(), task.id());
                        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(true), Optional.empty());
                    }

                    @Override
                    public OperationResult<Boolean> replacement(ServerId exact,
                                                                gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.Instance previous,
                                                                gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.Candidate candidate,
                                                                TaskContext task) {
                        assertTrue(nativeInstall == NativeInstallType.REPLACE
                            || nativeInstall == NativeInstallType.PENDING);
                        assertEquals("15.6", previous.version());
                        assertEquals("16.6", candidate.engineVersion());
                        assertEquals(observedTask.get(), task.id());
                        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(true), Optional.empty());
                    }

                    @Override
                    public OperationResult<Boolean> restored(ServerId exact,
                                                             gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.Instance restored,
                                                             TaskContext task) {
                        assertTrue(nativeInstall == NativeInstallType.REPLACE
                            || nativeInstall == NativeInstallType.PENDING);
                        assertEquals("16.6", restored.version());
                        assertEquals(observedTask.get(), task.id());
                        nativeState.restorePending = false;
                        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(true), Optional.empty());
                    }

                    public OperationResult<DeploymentStaticWorkflow.Selection> select(ServerId exact, String manifest,
                                                                                      List<DiscoveredProjectPart> discovered, TaskContext task) {
                        assertEquals(server, exact);
                        assertEquals(1, discovered.size());
                        reviewedTask.set(task);
                        observedTask.set(task.id());
                        manifestSha.set(manifest);
                        return new OperationResult<>(TaskResult.SUCCEEDED,
                            Optional.of(new DeploymentStaticWorkflow.Selection(discovered.getFirst().id(),
                                Map.of(discovered.getFirst().id(),
                                    new DeploymentStaticWorkflow.Choice(noPortNode
                                        ? DeploymentProjectKind.NODE_SERVICE
                                        : DeploymentProjectKind.GO_SERVICE, Set.of())),
                                Optional.empty())),
                            Optional.empty());
                    }

                    @Override
                    public OperationResult<Boolean> existingSchema(ServerId exact, String componentId,
                                                                   gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DatabaseRequirement database,
                                                                   String sourceDigest, TaskContext task) {
                        assertTrue(schemaReview);
                        assertEquals(server, exact);
                        assertEquals(observedTask.get(), task.id());
                        assertEquals(nativeState.sourceDigest, sourceDigest);
                        assertEquals("main", database.id());
                        assertEquals("sampledb", database.database());
                        assertEquals(List.of("schema.sql"), database.initializationFiles());
                        assertFalse(componentId.isBlank());
                        assertEquals(0, nativeState.schemaFailureRemaining);
                        assertFalse(nativeState.initialized);
                        try {
                            var databasePlan = DeploymentDatabasePlanActionLog.find(bound.tasks(), task).orElseThrow();
                            var originalReceipt = DeploymentNativeDatabaseActionLog.find(bound.tasks(), task,
                                    DeploymentNativeDatabaseActionLog.KEY + "." + databasePlan.facts().keys().getFirst())
                                .orElseThrow().facts();
                            assertEquals("initialize", originalReceipt.action());
                            assertEquals("FAILED", originalReceipt.phase());
                            assertEquals("NONE", originalReceipt.effectBoundary());
                        } catch (ApiException unavailable) {
                            throw new AssertionError("Original failed initialize receipt must remain readable",
                                unavailable);
                        }
                        assertEquals(currentPublication, ++nativeState.schemaReviews);
                        return new OperationResult<>(TaskResult.SUCCEEDED,
                            Optional.of(nativeInstall == NativeInstallType.EXISTING_SCHEMA_APPROVED
                                || nativeInstall == NativeInstallType.AUTH_SCHEMA_APPROVED),
                            Optional.empty());
                    }

                    public OperationResult<Map<String, DeploymentStaticWorkflow.Answers>> review(ServerId exact,
                                                                                                 String manifest, List<DeploymentStaticWorkflow.Prompt> prompts, TaskContext task) {
                        assertEquals(observedTask.get(), task.id());
                        var prompt = prompts.getFirst();
                        reviewedApplication.set(prompt.applicationId());
                        var values = new HashMap<>(prompt.defaults());
                        values.put("primary", "service");
                        values.put("secondary", "main.go");
                        values.put("version", "1.24");
                        values.put("port", "8080");
                        values.put("healthMode", "TCP");
                        if (noPortNode) {
                            assertEquals("", prompt.defaults().getOrDefault("port", ""));
                            values.put("primary", "");
                            values.put("secondary", "");
                            values.put("version", "22");
                            values.put("port", "");
                            values.put("healthMode", "PROCESS");
                        }
                        values.put("exposure", "INTERNAL");
                        values.put("secrets", "page-application:1");
                        var answer = new DeploymentStaticWorkflow.Answers(values, List.of(),
                            DeploymentBuildLimits.defaultNonRoot(), false, true);
                        workflow.validateReview(prompt, answer);
                        return new OperationResult<>(TaskResult.SUCCEEDED,
                            Optional.of(Map.of(prompt.componentId(), answer)), Optional.empty());
                    }

                    public OperationResult<Boolean> confirm(ServerId exact, String manifest,
                                                            Map<String, String> archives, Map<String, DeploymentStaticWorkflow.Answers> answers,
                                                            Map<String, gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentPreparedDatabase> databases,
                                                            Optional<DeploymentStaticWorkflow.GitReview> gitReview, TaskContext task) {
                        assertEquals(observedTask.get(), task.id());
                        if (sqlite) {
                            var prepared = databases.values().iterator().next();
                            var binding = prepared.bindings().getFirst();
                            var connection = (DeploymentDatabaseConnection.Sqlite) binding.connection();
                            String actualPath = prepared.configuration().stream()
                                .filter(value -> value.key().equals("DB_PATH")).findFirst().orElseThrow().value()
                                .canonicalValue();
                            assertEquals(connection.physicalPath(reviewedApplication.get(), binding.databaseId()),
                                actualPath);
                            assertEquals("seed.db", connection.seedFile());
                            assertEquals(List.of("schema.sql"), connection.initializationFiles());
                            assertTrue(prepared.schemaReview().orElseThrow().firstDeploymentInitializationPlanned());
                        } else if (nativeDatabase) {
                            var prepared = databases.values().iterator().next();
                            var binding = prepared.bindings().getFirst();
                            var connection = (DeploymentDatabaseConnection.Server) binding.connection();
                            assertEquals("sampledb", connection.database());
                            assertEquals("sampleuser", connection.username());
                            assertEquals("127.0.0.1", connection.host());
                            assertEquals(5432, connection.port());
                            assertEquals(expectedNativeRevision, connection.passwordReference().revision());
                            assertEquals(List.of(connection.passwordReference()), prepared.secrets());
                            assertEquals(nativeState.sourceDigest,
                                prepared.schemaReview().orElseThrow().inspectionSha256());
                        } else
                            assertTrue(databases.values().stream().allMatch(value -> value.bindings().isEmpty()));
                        assertEquals(git, gitReview.isPresent());
                        gitReview.ifPresent(value -> {
                            assertEquals(selectedSourceDirectory().toUri().toString(), value.remote());
                            assertEquals("DEFAULT:HEAD", value.reference());
                            assertEquals(commit, value.commit());
                        });
                        sha.set(archives.values().iterator().next());
                        candidate.set(new DeploymentRemoteWorkspace(reviewedApplication.get(), sha.get()));
                        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(true), Optional.empty());
                    }
                };
                var gitInput = git
                    ? Optional.of(new DeploymentStaticWorkflow.GitSource(
                    new DeploymentGitRequest(new DeploymentGitRemote(selectedSourceDirectory().toUri()),
                        new DeploymentGitRef.DefaultBranch(), Set.of(), 65536, true),
                    GIT))
                    : Optional.<DeploymentStaticWorkflow.GitSource>empty();
                var draft = new DeploymentStaticWorkflow.Draft(git ? "reviewed-git-project" : "", 65536, 100, 65536);
                var handle = git
                    ? GitRuntimeTestBridge.staticWorkflow(runtime, bound).submit(server, draft, gitInput, reviewer)
                    : workflow.submit(server, draft, gitInput, reviewer, fullControl,
                    fullControl ? "FULL_CONTROL" : "MANUAL_REVIEW");
                if (nativeInstall == NativeInstallType.START_UNKNOWN) {
                    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
                    while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < deadline)
                        Thread.sleep(25);
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), handle.snapshot().toString());
                    assertTrue(actions.contains("native-start"));
                    assertFalse(actions.contains("native-prepare"));
                    assertEquals(1,
                        scalar(runtime,
                            "SELECT COUNT(*) FROM platform_task_resource "
                                + "WHERE resource_kind='CUSTOM' AND resource_id='" + OWNER
                                + "/checkpoint/deployment-static-preparation' AND released_at IS NULL"));
                    assertTrue(
                        scalar(runtime,
                            "SELECT COUNT(*) FROM platform_task_resource "
                                + "WHERE resource_kind='FILE' AND released_at IS NULL") > 0,
                        "Original frozen source must remain owned while native effect is unknown");
                    System.out.println("ORIGINAL_NATIVE_WORKFLOW_UNKNOWN_VERIFIED");
                    System.exit(0);
                }
                assertTrue(callbackEnded.await(25, TimeUnit.SECONDS),
                    () -> handle.snapshot() + " result=" + handle.completion().toCompletableFuture().getNow(null)
                        + " actions=" + actions);
                OperationResult<Void> result;
                try {
                    result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                } catch (TimeoutException unfinished) {
                    throw new AssertionError(
                        "Static publication unfinished: " + handle.snapshot() + ", app=" + reviewedApplication.get()
                            + ", candidate=" + candidate.get() + ", actions=" + actions + ", resources="
                            + scalar(runtime,
                            "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"),
                        unfinished);
                }
                if (chainedAuthentication) {
                    assertEquals(2, actions.stream().filter("native-target"::equals).count());
                    assertEquals(0, nativeState.adminFailuresRemaining);
                    for (var owned : ownedInputs)
                        assertArrayEquals(new byte[owned.length], owned,
                            "Consumed reviewer-owned input must be cleared");
                }
                if (nativeInstall == NativeInstallType.EXISTING_SCHEMA_REFUSED || fullControl) {
                    if (fullControl) {
                        assertTrue(independentReviews.get() > 0, "Independent fixed-action approvals actually ran");
                        try (var access = scope(runtime, true);
                             var connection = access.openConnection();
                             var statement = connection.createStatement();
                             var rows = statement.executeQuery(
                                 "SELECT preference_key,preference_value FROM preference WHERE preference_key IN ('deploy_mode','deploy_approval')")) {
                            var choices = new HashMap<String, String>();
                            while (rows.next())
                                choices.put(rows.getString(1), rows.getString(2));
                            assertEquals(Map.of("deploy_mode", "ASSISTED", "deploy_approval", "FULL_CONTROL"), choices);
                        }
                    }
                    assertEquals(TaskResult.CANCELLED, result.outcome(), result.toString());
                    assertEquals("DEPLOYMENT_NATIVE_DB_DECLINED", result.problem().orElseThrow().eventCode());
                    assertFalse(nativeState.initialized);
                    assertEquals(1, nativeState.schemaReviews);
                    assertEquals(1, actions.stream().filter("native-initialize"::equals).count());
                    assertFalse(actions.contains("upload"));
                    assertFalse(actions.contains("publish"));
                    assertEquals(0, countMod(runtime, "deployment_release"));
                    assertEquals(0,
                        scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                    break;
                }
                if (schemaReview) {
                    assertEquals(publication, nativeState.schemaReviews);
                    assertEquals(2, actions.stream().filter("native-initialize"::equals).count());
                    assertTrue(nativeState.initialized);
                }
                assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
                assertEquals(publication, countMod(runtime, "deployment_release"));
                assertEquals(publication * (nativeDatabase ? 2 : 1),
                    countMod(runtime, "deployment_unit_release_secret_reference"));
                assertEquals(git ? publication : 0, countMod(runtime, "deployment_project"));
                if (git)
                    assertGitSourceSaved(runtime, selectedSourceDirectory().toUri().toString(), commit,
                        manifestSha.get());
                assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                assertEquals(1, actions.stream().filter("publish"::equals).count());
                assertEquals(1, actions.stream().filter("upload"::equals).count());
                assertEquals(installHelper && publication == 1 ? 1 : 0, helperInstalls.get());
                assertEquals(1, actions.stream().filter("stage-secret-page-application"::equals).count(),
                    "The reviewed revision and protected bytes must reach the controlled stage command");
                if (noPortNode) {
                    var sentReleases = actions.stream().filter(value -> value.startsWith("wire-release="))
                        .map(value -> value.substring("wire-release=".length())).toList();
                    assertEquals(1, sentReleases.size());
                    String publishedRelease = sentReleases.getFirst();
                    assertNotEquals(sha.get(), publishedRelease,
                        "The complete reviewed release identity also binds runtime, configuration and tools");
                    assertEquals(1, actions.stream().filter("observe"::equals).count(),
                        "Successful DAEMON publication must pass the actual typed RUNNING observation");
                    var inspectionFailure = new AtomicReference<Throwable>();
                    var inspected = original.tasks().submit(new TaskRequest("node-process-publication-inspect",
                        "Inspect original process publication", List.of(server.id()), false), task -> {
                        try {
                            var graph = new DeploymentGraphStore(original.db())
                                .find(task, reviewedApplication.get()).orElseThrow();
                            assertInstanceOf(DeploymentHealthCheck.Process.class, graph.healthCheck());
                            var component = graph.components().getFirst();
                            assertInstanceOf(DeploymentRuntimeDefinition.NodeService.class,
                                component.reviewedRuntime());
                            assertInstanceOf(DeploymentHealthCheck.Process.class,
                                component.runtime().healthCheck());
                            assertTrue(component.runtime().userAccessUrl().isEmpty());
                            assertEquals(22, ((DeploymentRuntimeDefinition.NodeService) component.reviewedRuntime()).nodeMajorVersion());
                            assertEquals(DeploymentWorkload.ExecutionMode.DAEMON, component.runtime().workload().mode());
                            assertTrue(component.runtime().workload().endpoints().isEmpty());
                            UUID applicationReleaseId;
                            String applicationReleaseSha;
                            UUID originalExecutionId;
                            try (var connection = original.db().openConnection(task);
                                 var query = connection.prepareStatement(
                                     "SELECT u.current_release_sha256,e.outcome,r.release_sha256,e.platform_task_id,e.phase,e.business_committed,e.cleanup_complete,r.release_id,e.execution_id "
                                         + "FROM deployment_unit u JOIN managed_application_component c ON c.deployment_unit_id=u.deployment_unit_id "
                                         + "JOIN deployment_release r ON r.application_id=c.application_id "
                                         + "JOIN deployment_execution e ON e.execution_id=r.execution_id "
                                         + "WHERE u.deployment_unit_id=? AND c.application_id=? AND r.state='ACTIVE'")) {
                                query.setString(1, component.unit().id());
                                query.setString(2, graph.applicationId());
                                try (var rows = query.executeQuery()) {
                                    assertTrue(rows.next());
                                    assertEquals(publishedRelease, rows.getString(1));
                                    assertEquals("SUCCEEDED", rows.getString(2));
                                    applicationReleaseSha = rows.getString(3);
                                    assertEquals(handle.id(), rows.getObject(4, UUID.class));
                                    assertEquals("FINISHED", rows.getString(5));
                                    assertTrue(rows.getBoolean(6));
                                    assertTrue(rows.getBoolean(7));
                                    applicationReleaseId = rows.getObject(8, UUID.class);
                                    originalExecutionId = rows.getObject(9, UUID.class);
                                    assertFalse(rows.next());
                                }
                            }
                            var releases = new DeploymentReleaseStore(original.db());
                            var applicationRelease = releases.find(task, graph.applicationId(), applicationReleaseId).orElseThrow();
                            assertEquals(applicationReleaseSha, applicationRelease.identity().sha256());
                            assertEquals(originalExecutionId, applicationRelease.executionId());
                            assertEquals(DeploymentReleaseRecord.DeploymentReleaseState.ACTIVE, applicationRelease.state());
                            var retained = releases.snapshot(applicationRelease);
                            assertEquals(graph, retained.graph());
                            assertEquals(1, retained.bindings().size());
                            assertEquals(component.unit().id(), retained.bindings().getFirst().unitId());
                            assertEquals(publishedRelease, retained.bindings().getFirst().releaseSha256());
                            return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(),
                                Optional.empty());
                        } catch (Exception | AssertionError failed) {
                            inspectionFailure.set(failed);
                            throw failed;
                        }
                    });
                    try {
                        assertEquals(TaskResult.SUCCEEDED,
                            inspected.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
                    } catch (TimeoutException unfinished) {
                        var failure = new AssertionError("Original Node publication inspection unfinished: publication="
                            + handle.snapshot() + ", inspection=" + inspected.snapshot() + ", originalFailure="
                            + inspectionFailure.get() + ", actions=" + actions + ", activeResources="
                            + scalar(runtime,
                            "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"),
                            unfinished);
                        if (inspectionFailure.get() != null)
                            failure.addSuppressed(inspectionFailure.get());
                        throw failure;
                    }
                    assertEquals(0,
                        scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                }
                if (sqlite) {
                    long revision = publication;
                    var inspected = original.tasks().submit(new TaskRequest("sqlite-publication-inspect",
                        "Inspect published SQLite", List.of(server.id()), false), task -> {
                        var graph = new DeploymentGraphStore(original.db())
                            .find(task, reviewedApplication.get()).orElseThrow();
                        var component = graph.components().getFirst();
                        var binding = component.resources().databaseBindings().orElseThrow().getFirst();
                        assertEquals("main", binding.databaseId());
                        var connection = (DeploymentDatabaseConnection.Sqlite) binding.connection();
                        assertEquals("seed.db", connection.seedFile());
                        assertEquals(List.of("schema.sql"), connection.initializationFiles());
                        var configuration = new DeploymentConfigStore(original.db())
                            .find(task, reviewedApplication.get(), revision).orElseThrow();
                        assertEquals(connection.physicalPath(reviewedApplication.get(), binding.databaseId()),
                            configuration.requireValue("DB_PATH", DeploymentConfigScopeType.RUNTIME)
                                .canonicalValue());
                        return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                    });
                    assertEquals(TaskResult.SUCCEEDED,
                        inspected.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
                }
                if (nativeDatabase) {
                    assertTrue(actions.contains("native-inspect"));
                    assertTrue(actions.contains("native-start"));
                    assertEquals(
                        (nativeInstall == NativeInstallType.FIRST || nativeInstall == NativeInstallType.REPLACE)
                            && publication == 1 ? 1 : 0,
                        actions.stream().filter("native-install"::equals).count());
                    assertEquals(
                        (nativeInstall == NativeInstallType.REPLACE || nativeInstall == NativeInstallType.PENDING)
                            && publication == 1 ? 1 : 0,
                        actions.stream().filter("native-resume"::equals).count());
                    assertTrue(actions.contains("native-target"));
                    assertTrue(actions.contains("native-prepare"));
                    assertTrue(actions.contains("native-initialize"));
                    assertEquals(expectedNativeRevision, countMod(runtime, "secret_revision") - 1,
                        "Only a successful new password may add a database credential revision");
                    if (authRetry || rotateStored && publication == 2)
                        assertEquals(2, passwordPrompts.get());
                    long revision = publication;
                    var inspected = original.tasks().submit(new TaskRequest("native-publication-inspect",
                        "Inspect published native database", List.of(server.id()), false), task -> {
                        var graph = new DeploymentGraphStore(original.db())
                            .find(task, reviewedApplication.get()).orElseThrow();
                        var binding = graph.components().getFirst().resources().databaseBindings().orElseThrow()
                            .getFirst();
                        var connection = (DeploymentDatabaseConnection.Server) binding.connection();
                        assertEquals("main", binding.databaseId());
                        assertEquals("sampledb", connection.database());
                        assertEquals(expectedNativeRevision, connection.passwordReference().revision());
                        if (rotateStored) {
                            var repository = new DeploymentSecretStore(original.db());
                            var initialReference = new DeploymentSecretRef(
                                connection.passwordReference().identifier(), 1);
                            var initial = repository.findRevision(task, initialReference).orElseThrow();
                            if (currentPublication == 1) {
                                firstNativeSecret.set(initial);
                                firstNativePasswordDigest.set(nativeState.passwordDigest);
                            } else {
                                assertEquals(firstNativeSecret.get(), initial,
                                    "The old immutable credential revision changed");
                                var latest = repository.findRevision(task, connection.passwordReference())
                                    .orElseThrow();
                                assertNotEquals(initial.credentialId(), latest.credentialId(),
                                    "The corrected password must create a new protected value");
                                try {
                                    boolean firstMatches = original.secrets().use(
                                        new SecretId(initial.credentialId(),
                                            initial.purpose().orElseThrow()),
                                        initial.purpose().orElseThrow(), task,
                                        secretBytes -> firstNativePasswordDigest.get()
                                            .equals(DeploymentNativeDatabaseWire.digest(secretBytes)));
                                    boolean secondMatches = original.secrets()
                                        .use(new SecretId(latest.credentialId(),
                                                latest.purpose().orElseThrow()),
                                            latest.purpose().orElseThrow(), task,
                                            secretBytes -> Arrays.equals(
                                                "rotated-password".getBytes(StandardCharsets.UTF_8),
                                                secretBytes));
                                    nativeSecretAudit.set("old=" + firstMatches + ",new=" + secondMatches);
                                } catch (ApiException failure) {
                                    nativeSecretAudit.set(failure.problem().eventCode());
                                }
                            }
                        }
                        var configuration = new DeploymentConfigStore(original.db())
                            .find(task, reviewedApplication.get(), revision).orElseThrow();
                        assertEquals("127.0.0.1",
                            configuration.requireValue("DB_HOST", DeploymentConfigScopeType.RUNTIME)
                                .canonicalValue());
                        assertEquals("sampledb",
                            configuration
                                .requireValue("DB_DATABASE", DeploymentConfigScopeType.RUNTIME)
                                .canonicalValue());
                        return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                    });
                    assertEquals(TaskResult.SUCCEEDED,
                        inspected.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
                    if (rotateStored && currentPublication == 2)
                        assertEquals("old=true,new=true", nativeSecretAudit.get(),
                            "Both immutable protected values must retain their original bytes");
                }
                var snapshots = configurationRows(runtime);
                String owner = ownershipManifest(runtime);
                assertEquals(publication, snapshots.size());
                assertEquals(publication + ":", snapshots.getLast().substring(0, 2));
                if (firstSnapshot == null) {
                    firstSnapshot = snapshots;
                    firstOwner = owner;
                    firstApplication = reviewedApplication.get();
                } else {
                    assertEquals(firstSnapshot.getFirst(), snapshots.getFirst(), "First immutable revision changed");
                    assertEquals(firstOwner, owner, "Managed ownership manifest changed on second publication");
                    assertEquals(firstApplication, reviewedApplication.get(), "Managed application changed");
                }
            }
        }
    }

    /** Runs only fixture setup commands against the controlled local repository. / 仅对受控本地仓库运行夹具准备命令。
     * @param directory original fixture repository / 原夹具仓库
     * @param arguments fixed Git arguments / 固定 Git 参数
     * @return bounded command output / 有界命令输出
     * @throws Exception if native fixture preparation fails / 原生夹具准备失败时 */
    private static String git(Path directory, String... arguments) throws Exception {
        var words = new ArrayList<String>();
        words.add(GIT.toString());
        words.addAll(List.of(arguments));
        var process = new ProcessBuilder(words).directory(directory.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().close();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    /** Verifies actual saved source identity and formal execution linkage after task archival. / 在任务归档后核对实际来源身份及正式执行关联。
     * @param runtime actual host / 真实宿主
     * @param remote original credential-free URL / 原不含凭据 URL
     * @param commit pinned commit / 固定提交
     * @param manifest original frozen manifest digest / 原冻结清单摘要
     * @throws Exception if actual Mod data cannot be read / 真实 Mod 数据无法读取时 */
    private void assertGitSourceSaved(PlatformRuntime runtime, String remote, String commit, String manifest)
        throws Exception {
        try (var access = scope(runtime, true);
             var connection = access.openConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT p.source_location,p.git_ref,p.git_commit,p.source_sha256 "
                 + "FROM deployment_execution e JOIN deployment_project p ON e.project_id=p.project_id "
                 + "ORDER BY e.created_at DESC LIMIT 1")) {
            assertTrue(rows.next(), "Formal execution must retain its reviewed source project");
            assertEquals(remote, rows.getString(1));
            assertEquals("DEFAULT:HEAD", rows.getString(2));
            assertEquals(commit, rows.getString(3));
            assertEquals(manifest, rows.getString(4));
        }
    }

    /** Reads immutable revision identities from the actual Mod schema. / 从真实 Mod 模式读取不可变修订身份。
     * @param runtime actual host / 真实宿主
     * @return ordered revision and digest rows / 排序后的修订和摘要行
     * @throws Exception if schema access fails / 模式访问失败时 */
    private List<String> configurationRows(PlatformRuntime runtime) throws Exception {
        try (var access = scope(runtime, true);
             var connection = access.openConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT configuration_revision, configuration_sha256 "
                 + "FROM deployment_unit_configuration_snapshot ORDER BY configuration_revision")) {
            var result = new ArrayList<String>();
            while (rows.next())
                result.add(rows.getLong(1) + ":" + rows.getString(2));
            return List.copyOf(result);
        }
    }

    /** Reads the actual managed ownership manifest, independent of the reviewed name. / 读取独立于审阅名称的实际受管归属清单。
     * @param runtime actual host / 真实宿主
     * @return persisted owner identity / 已持久归属身份
     * @throws Exception if business storage fails / 业务存储失败时 */
    private String ownershipManifest(PlatformRuntime runtime) throws Exception {
        try (var access = scope(runtime, true);
             var connection = access.openConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT ownership_manifest_sha256 FROM deployment_unit")) {
            assertTrue(rows.next());
            var digest = rows.getString(1);
            assertFalse(rows.next(), "Expected one managed unit across publications");
            return digest;
        }
    }

    /** Verifies the retained original task in its own JVM, whose exit models an interrupted host. / 在独立JVM验证保留的原任务，其退出模拟宿主中断。
     * @throws Exception if the controlled child cannot finish / 受控子进程无法完成时 */
    @ParameterizedTest
    @EnumSource(value = ScenarioType.class, names = {"SECOND_CONFIG_UNKNOWN", "PUBLISH_UNKNOWN",
        "HEALTH_FAILED_ROLLBACK_CLOSE_UNKNOWN", "HEALTH_FAILED_ROLLBACK_UNSUPPORTED_MODE", "ROLLBACK_UNKNOWN", "RETAIN_UNKNOWN"})
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

    /** The real static submit path retains its preparation marker and frozen source on an unknown native effect. / 真实静态提交路径遇原生效果未知时保留准备标记及冻结源码。
     * @throws Exception if the isolated original task cannot be observed / 无法观察隔离原任务时 */
    @Test
    void nativeStartUnknownRetainsOriginalStaticMarkerAndSource() throws Exception {
        var javaBinary = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        var process = new ProcessBuilder(javaBinary.toString(), "-cp", System.getProperty("java.class.path"),
            getClass().getName(), "--native-unknown", root.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(55, TimeUnit.SECONDS), "Original native workflow probe timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("ORIGINAL_NATIVE_WORKFLOW_UNKNOWN_VERIFIED"), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    /** Runs only the controlled unknown case when launched by the parent test. / 仅在父测试启动时运行受控未知场景。
     * @param args fixed child marker, directory and scenario / 固定子进程标记、目录与场景 */
    public static void main(String[] args) {
        if (args.length == 2 && args[0].equals("--native-unknown")) {
            try {
                var probe = new DeploymentStandardPublishRunnerTest();
                probe.root = Path.of(args[1]);
                probe.runStaticWorkflow(false, false, false, true, false, false, NativeInstallType.START_UNKNOWN);
                throw new AssertionError("Original native workflow unexpectedly returned");
            } catch (Throwable failed) {
                failed.printStackTrace();
                System.exit(1);
            }
        }
        if ((args.length != 3 && args.length != 4) || !args[0].equals("--unknown"))
            throw new IllegalArgumentException("Isolated publication probe only");
        try {
            var probe = new DeploymentStandardPublishRunnerTest();
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
            var server = new ServerId("server-publication");
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
            var original = new DeploymentRefFlow(bound);
            var executor = new DeploymentStandardPublishRunner(bound, original);
            var unit = DeploymentUnitIdentity.forManaged("service", server, "b".repeat(64));
            var reference = new DeploymentSecretRef("password-id", 1);
            boolean purposeCase = scenario == ScenarioType.PURPOSE_PRESENT || scenario == ScenarioType.PURPOSE_UNKNOWN
                || scenario == ScenarioType.PURPOSE_MISMATCH;
            boolean purposeDefect = scenario == ScenarioType.PURPOSE_UNKNOWN
                || scenario == ScenarioType.PURPOSE_MISMATCH;
            var secret = purposeCase ? purposeSecret(real, reference, scenario == ScenarioType.PURPOSE_UNKNOWN) : null;
            if (scenario == ScenarioType.PURPOSE_UNKNOWN) {
                var choices = real.tasks().submit(
                    new TaskRequest("legacy-secret-choices", "Legacy secret choices", List.of(), true),
                    task -> new OperationResult<>(TaskResult.SUCCEEDED,
                        Optional.of(new DeploymentSecretStore(real.db()).listReferences(task, 1000)),
                        Optional.empty()));
                var listed = choices.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
                assertEquals(TaskResult.SUCCEEDED, listed.outcome());
                assertTrue(listed.value().orElseThrow().stream().noneMatch(item -> item.reference().equals(reference)),
                    "A legacy revision without its original purpose cannot be selected");
            }
            var reviewedSecret = purposeCase && scenario == ScenarioType.PURPOSE_MISMATCH
                ? new SecretId(secret.id(), "deployment.wrong")
                : secret;
            var credentials = purposeCase
                ? Map.of(reference, reviewedSecret)
                : Map.<DeploymentSecretRef, SecretId>of();
            var handle = executor.submit(unit, Optional.empty(), credentials, task -> {
                if (recoveryPort != 22) {
                    var policy = DeploymentPublishPolicyFacts.begin(real, task, server,
                        "STATIC", "MANUAL_REVIEW", "GENERAL", "");
                    var connected = real.linux().connect(policy.facts().fixedTarget(), task);
                    policy.authenticated(connected.facts().fingerprint());
                }
                var selection = real.file().sources().selectDirectory("Source", task);
                var material = new DeploymentSourceSetup(real.file()).prepare(selection, 65536, 100, task);
                var archive = new DeploymentSourceArchiver(real.file()).prepare(material, 65536, task);
                archiveSha.set(archive.sha256());
                archiveBytes.set(archive.byteCount());
                archivePath.set(archive.path());
                var analysis = new DeploymentStandardAnalyzer().analyze(material, "service",
                    DeploymentProjectKind.GO_SERVICE, Optional.empty(), false, task);
                var facts = analysis.value().orElseThrow().assessment().facts().orElseThrow();
                candidate.set(new DeploymentRemoteWorkspace(facts.applicationId(), archive.sha256()));
                assertEquals(unit.id(), facts.applicationId());
                var request = new ReviewedDeploymentRequest(server, facts, material, archive,
                    DeploymentConfigSnapshot.create(facts.applicationId(), 1, "v1", Instant.now(),
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
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(plan.value().orElseThrow()),
                    Optional.empty());
            });
            if (purposeDefect) {
                var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                assertEquals(TaskResult.FAILED, result.outcome(), result.toString());
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
                        "Publish unfinished: " + handle.snapshot() + ", actions=" + actions + ", releases="
                            + countMod(runtime, "deployment_release") + ", resources="
                            + scalar(runtime,
                            "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"),
                        unfinished);
                }
                assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
                assertEquals(1, countMod(runtime, "deployment_release"));
                if (scenario == ScenarioType.PURPOSE_PRESENT) {
                    assertNotEquals(reference.identifier(), reviewedSecret.purpose());
                    assertEquals(1, countReleaseSecrets(runtime));
                }
                assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                assertEquals(List.of("maintenance-end", "retain", "candidate-cleanup"),
                    actions.subList(actions.size() - 3, actions.size()));
            } else if (scenario == ScenarioType.HEALTH_FAILED_ROLLBACK) {
                var result = handle.completion().toCompletableFuture().get(12, TimeUnit.SECONDS);
                assertEquals(TaskResult.FAILED, result.outcome(), result.toString());
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
                } else if (scenario == ScenarioType.HEALTH_FAILED_ROLLBACK_UNSUPPORTED_MODE) {
                    assertTrue(actions.contains("rollback"));
                    assertFalse(actions.contains("candidate-cleanup"));
                    assertFalse(actions.contains("observe-old-ordinary"));
                    assertTrue(scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL") > 0);
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
    private SecretId purposeSecret(ModContext context, DeploymentSecretRef reference, boolean unknownPurpose)
        throws Exception {
        var observed = new AtomicReference<SecretId>();
        var setup = context.tasks()
            .submit(new TaskRequest("secret_purpose_fixture", "Secret purpose fixture", List.of(), true), task -> {
                var secret = context.secrets().create("deployment.actual", new byte[]{7}, task);
                observed.set(secret);
                var reservation = context.secrets().reserve(secret, "secret:" + reference.identifier(), task);
                new DeploymentSecretStore(context.db()).saveRevision(task, new DeploymentSecretRevision(
                    reference, secret.id(), Instant.now(), Optional.of(secret.purpose())));
                if (unknownPurpose)
                    try (var connection = context.db().openConnection(task);
                         var statement = connection.prepareStatement(
                             "UPDATE secret_revision SET purpose=NULL WHERE secret_id=? AND secret_revision=?")) {
                        statement.setString(1, reference.identifier());
                        statement.setLong(2, reference.revision());
                        assertEquals(1, statement.executeUpdate());
                    }
                context.secrets().resolve(reservation.id(), ResourceRefResult.REFERENCED, task);
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
        assertEquals(TaskResult.SUCCEEDED, setup.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
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
        var secondSelection = context.file().sources().selectDirectory("Same source", task);
        try (var material = new DeploymentSourceSetup(context.file()).prepare(secondSelection, 65536, 100, task);
             var archive = new DeploymentSourceArchiver(context.file()).prepare(material, 65536, task)) {
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
            DeploymentConfigSnapshot.create(request.facts().applicationId(), 2, "v1", Instant.now(),
                List.of()),
            request.secretReferences(), request.resources(), request.runtime(), request.userAccessUrl(),
            request.limits(), request.review(), false, true);
        assertNotEquals(original, DeploymentReviewedReleaseIdentity.from(changedConfiguration));
        var firstSecret = new ReviewedDeploymentRequest(request.server(), request.facts(), request.material(),
            request.archive(), request.configuration(), List.of(new DeploymentSecretRef("db-password", 1)),
            request.resources(), request.runtime(), request.userAccessUrl(), request.limits(), request.review(),
            false, true);
        var secondSecret = new ReviewedDeploymentRequest(request.server(), request.facts(), request.material(),
            request.archive(), request.configuration(), List.of(new DeploymentSecretRef("db-password", 2)),
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
                 + "WHERE task_id=? AND resource_kind='CUSTOM' AND resource_id=? AND released_at IS NULL")) {
            query.setObject(1, task);
            query.setString(2, OWNER + "/checkpoint/deployment-publication-remote");
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
        assertTrue(Set.of("deployment_release", "deployment_project", "deployment_unit_release_secret_reference",
            "secret_revision").contains(table));
        try (var access = scope(runtime, true);
             var connection = access.openConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
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
    private ModContext withFullControlModels(ModContext real, AtomicReference<TaskContext> originalTask,
                                             AtomicInteger independentReviews) {
        AiService models = new AiService() {
            @Override
            public gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.appapi.ai.voice.VoiceResponse> call(
                gold.debug.windowstolinux.appapi.ai.voice.VoiceRequest request, gold.debug.windowstolinux.appapi.task.run.TaskContext task) {
                throw new AssertionError("Voice is outside this fixture");
            }

            @Override
            public gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.appapi.ai.voice.VoiceResponse> call(
                gold.debug.windowstolinux.appapi.ai.voice.VoiceRequest request, String expectedRevision,
                gold.debug.windowstolinux.appapi.task.run.TaskContext task) {
                throw new AssertionError("Voice is outside this fixture");
            }

            @Override
            public gold.debug.windowstolinux.appapi.ai.voice.VoiceSession openVoice(
                gold.debug.windowstolinux.appapi.ai.voice.VoiceOptions options, gold.debug.windowstolinux.appapi.ai.voice.VoiceListener listener,
                gold.debug.windowstolinux.appapi.task.run.TaskContext task) {
                throw new AssertionError("Voice is outside this fixture");
            }

            public String configurationRevision(AiPurpose purpose, TaskContext task) {
                assertTrue(purpose == AiPurpose.LANGUAGE || purpose == AiPurpose.APPROVAL);
                return "a".repeat(64);
            }

            public OperationResult<AiResponse> call(AiRequest request, TaskContext task) throws ApiException {
                return call(request, "a".repeat(64), task);
            }

            public OperationResult<AiResponse> call(AiRequest request, String revision, TaskContext task) {
                assertEquals("a".repeat(64), revision);
                assertSame(originalTask.get(), task);
                try {
                    var facts = new ObjectMapper().readTree(request.messages().getLast().text());
                    if (request.purpose() == AiPurpose.APPROVAL) {
                        assertEquals("FULL_CONTROL", facts.path("policy").textValue());
                        assertFalse(facts.path("operation").textValue().isBlank());
                        independentReviews.incrementAndGet();
                    }
                    String output = request.purpose() == AiPurpose.APPROVAL
                        ? "{\"decision\":\"APPROVE\",\"risk\":\"NORMAL\",\"reason\":\"Exact fixed effect only\"}"
                        : "{\"action\":\"ADVISE\",\"sourceRevision\":\"" + facts.path("sourceRevision").textValue()
                        + "\",\"arguments\":{\"suggestions\":[],\"summary\":\"Keep deterministic inputs\",\"unresolved\":[]}}";
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse(output,
                        "controlled-independent", "stop", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))),
                        Optional.empty());
                } catch (Exception invalid) {
                    throw new AssertionError(invalid);
                }
            }
        };
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> {
                if (method.getName().equals("ai"))
                    return models;
                try {
                    return method.invoke(real, args);
                } catch (InvocationTargetException failed) {
                    throw failed.getCause();
                }
            });
    }

    private ModContext withControlledSession(ModContext real, RemoteSession session, PlatformRuntime runtime,
                                             ScenarioType scenario) {
        var commitRefused = new AtomicBoolean();
        LinuxService linux = (LinuxService) Proxy.newProxyInstance(LinuxService.class.getClassLoader(),
            new Class<?>[]{LinuxService.class}, (proxy, method, args) -> {
                if (method.getName().equals("pendingTransfers"))
                    return real.linux().pendingTransfers((TaskContext) args[0]);
                assertEquals("connect", method.getName());
                if (args[0] instanceof RemoteTarget expected) {
                    var task = (TaskContext) args[1];
                    var server = real.servers().get(expected.server(), task);
                    assertEquals("server-publication", expected.server().id());
                    assertEquals(task.id(), expected.taskId());
                    assertEquals(server.ref(), expected.server());
                    assertEquals(server.host(), expected.host());
                    assertEquals(server.port(), expected.port());
                    assertEquals(server.username(), expected.username());
                    expected.fingerprint().ifPresent(value -> assertEquals(value, session.facts().fingerprint(),
                        "The fixed expectation binds the controlled peer's observed wire identity"));
                } else {
                    assertEquals("server-publication", ((ServerId) args[0]).id());
                }
                return session;
            });
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> {
                if (method.getName().equals("linux"))
                    return linux;
                if (method.getName().equals("db") && scenario == ScenarioType.COMMIT_WINDOW)
                    return new ModDatabase() {
                        public gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity identity() throws ApiException {
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
                    return Proxy.newProxyInstance(InteractionService.class.getClassLoader(),
                        new Class<?>[]{InteractionService.class}, (interactionProxy, operation, arguments) -> {
                            try {
                                var result = operation.invoke(real.interaction(), arguments);
                                if ((operation.getName().equals("approve") || operation.getName().equals("approveRequired"))
                                    && result instanceof OperationResult<?> approved)
                                    approved.value().filter(ApprovalGrant.class::isInstance).map(ApprovalGrant.class::cast)
                                        .ifPresent(value -> grants.put(value, (ActionIntent) arguments[0]));
                                return result;
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
                try {
                    return method.invoke(real, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
    }

    /** Adds one initially missing helper to the existing controlled publication session. / 为既有受控发布会话补入一次初始缺失的 helper。
     * @param existing original controlled publication session / 原受控发布会话
     * @param context original Mod capabilities / 原 Mod 能力
     * @param task current reviewed task / 当前审阅任务
     * @param installs actual installer dispatch count / 实际安装发送次数
     * @return borrowed controlled session / 借用受控会话 */
    private static RemoteSession missingHelper(RemoteSession existing, ModContext context,
                                               AtomicReference<TaskContext> task, AtomicInteger installs) {
        var closes = new AtomicInteger();
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
            new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                if (method.getName().equals("execute")) {
                    var request = (CommandRequest) args[0];
                    if (request.script().contains("sha256sum --check --status") && installs.get() == 0)
                        return reply(1, "");
                    if (request.script().contains("--noprofile")) {
                        String input = new String(request.stdin(), StandardCharsets.UTF_8);
                        if (input.contains("prepare_stage=platform-packages")) {
                            installs.incrementAndGet();
                            return reply(
                                "HELPER_INSTALL=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION
                                    + "\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
                        }
                        if (input.contains("bootstrap_receipt")) {
                            var facts = DeploymentProtocolBootstrapActionLog.find(context.tasks(), task.get())
                                .orElseThrow().facts();
                            return reply(DeploymentHelperInstallerScript.receipt(task.get().id(),
                                facts.operationId(), facts.serverDigest(), facts.bodyDigest(), "SUCCEEDED"));
                        }
                    }
                }
                if (method.getName().equals("close") && closes.incrementAndGet() == 1)
                    return null;
                try {
                    return method.invoke(existing, args);
                } catch (InvocationTargetException wrapped) {
                    throw wrapped.getCause();
                }
            });
    }

    /** Non-secret state of one controlled native target across two source-identical publications. / 两次同源发布间一个受控原生目标的不含秘密状态。 */
    static final class NativeState {
        /** Controlled package installation case. / 受控软件包安装场景。 */
        NativeInstallType install = NativeInstallType.NONE;

        /** Whether the package is now installed. / 软件包现在是否已安装。 */
        boolean installed = true;

        /** Whether an incompatible package was replaced. / 不兼容软件包是否已替换。 */
        boolean replaced;

        /** Whether a prior replacement still requires manual restoration. / 前次替换是否仍需人工恢复。 */
        boolean restorePending;

        /** Exact component inspection digest. / 精确组件检查摘要。 */
        String sourceDigest = "";

        /** Final managed application identity. / 最终受管应用身份。 */
        String applicationId = "";

        /** Hash of the first actual transient application password, never the original bytes. / 首次真实短期应用密码的摘要，绝非原字节。 */
        private String passwordDigest = "";

        /** One controlled known authentication failure. / 一次受控已知认证失败。 */
        private int authFailuresRemaining;

        private int adminFailuresRemaining;

        /** Permits one confirmed new password after a stored revision is rejected. / 已存修订被拒后允许一份已确认新密码。 */
        private boolean acceptRotation;

        /** Whether prepare created the owned target. / prepare 是否创建了已归属目标。 */
        boolean prepared;

        /** Whether reviewed SQL reached a terminal initialization result. / 经审阅 SQL 是否到达终态初始化结果。 */
        boolean initialized;

        /** Remaining no-effect schema refusal. / 剩余无效果结构拒绝。 */
        int schemaFailureRemaining;

        /** Actual independent decisions. / 实际独立决定。 */
        int schemaReviews;
    }

    /** Owns decoded sensitive test-frame bytes until the current helper observation ends. / 持有已解码敏感测试帧字节直到当前 helper 观察结束。 */
    private static final class NativeFrame implements AutoCloseable {
        /** Only non-secret reviewed fields. / 仅非秘密已审阅字段。 */
        private final Map<String, String> fields = new HashMap<>();

        /** Clearable sensitive input bytes. / 可清零敏感输入字节。 */
        private final Map<String, byte[]> sensitive = new HashMap<>();

        /** SHA-256 of actual received frame bytes. / 实际收到帧字节的 SHA-256。 */
        private String digest;

        /** Clears all decoded sensitive values. / 清零全部已解码敏感值。 */
        @Override
        public void close() {
            sensitive.values().forEach(value -> Arrays.fill(value, (byte) 0));
        }
    }

    /** Decodes actual bounded stdin without making plaintext passwords or SQL into Strings. / 解码真实有界 stdin，密码和 SQL 明文绝不转 String。
     * @param request actual approved remote command / 真实已批准远端命令
     * @return owned clearable test frame / 所属可清零测试帧 */
    private static NativeFrame nativeFrame(CommandRequest request) {
        byte[] input = request.stdin();
        var decoded = new NativeFrame();
        try {
            decoded.digest = DeploymentNativeDatabaseWire.digest(input);
            assertTrue(input.length <= 3 * 1024 * 1024);
            int cursor = 0;
            while (cursor < input.length) {
                int equal = cursor;
                while (equal < input.length && input[equal] != '=')
                    equal++;
                int end = equal;
                while (end < input.length && input[end] != '\n')
                    end++;
                assertTrue(equal > cursor && end < input.length);
                String key = new String(input, cursor, equal - cursor, StandardCharsets.US_ASCII);
                byte[] encoded = Arrays.copyOfRange(input, equal + 1, end);
                byte[] value;
                try {
                    value = Base64.getDecoder().decode(encoded);
                } finally {
                    Arrays.fill(encoded, (byte) 0);
                }
                if (Set.of("applicationPassword", "adminPassword", "sql").contains(key))
                    assertNull(decoded.sensitive.put(key, value));
                else {
                    try {
                        assertNull(decoded.fields.put(key, new String(value, StandardCharsets.UTF_8)));
                    } finally {
                        Arrays.fill(value, (byte) 0);
                    }
                }
                cursor = end + 1;
            }
            return decoded;
        } catch (RuntimeException | Error failure) {
            decoded.close();
            throw failure;
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    /** Adds strict non-secret native helper facts to the existing controlled publication peer. / 向既有受控发布对等端补入严格非秘密原生 helper 事实。
     * @param existing existing publication peer / 既有发布对等端
     * @param context actual CLASS/H2 capabilities / 真实 CLASS/H2 能力
     * @param task current original task / 当前原任务
     * @param state one original target's observed state / 一个原目标的观察状态
     * @param actions observed action names / 已观察动作名
     * @return borrowed combined peer / 借用组合对等端 */
    static RemoteSession nativePeer(RemoteSession existing, ModContext context, AtomicReference<TaskContext> task,
                                    NativeState state, List<String> actions, Map<ApprovalGrant, ActionIntent> grants) {
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
            new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                if (method.getName().equals("execute")) {
                    var request = (CommandRequest) args[0];
                    String script = request.script();
                    int offset = script.indexOf(" native-db ");
                    if (offset >= 0) {
                        assertTrue(grants.containsKey(args[1]));
                        String action = script.substring(offset + " native-db ".length());
                        actions.add("native-" + action);
                        try (var frame = nativeFrame(request)) {
                            assertEquals("POSTGRESQL", frame.fields.get("engine"));
                            if (action.equals("inspect"))
                                return nativeReply(nativeInventory(state, ""));
                            if (!action.equals("install"))
                                assertEquals("postgresql-16", frame.fields.get("instanceId"));
                            if (!Set.of("start", "resume", "install").contains(action)) {
                                assertEquals("sampledb", frame.fields.get("database"));
                                assertEquals("sampleuser", frame.fields.get("username"));
                                assertEquals(state.applicationId, frame.fields.get("applicationId"));
                            }
                            if (action.equals("target")) {
                                if (state.adminFailuresRemaining > 0) {
                                    assertFalse(frame.sensitive.containsKey("adminPassword"));
                                    state.adminFailuresRemaining--;
                                    return nativeReply(Map.of("status", "AUTH_REQUIRED"));
                                }
                                if (state.install == NativeInstallType.AUTH_SCHEMA_APPROVED
                                    || state.install == NativeInstallType.AUTH_SCHEMA_FULL_CONTROL_REFUSED)
                                    assertArrayEquals("fresh-administrator".getBytes(StandardCharsets.UTF_8),
                                        frame.sensitive.get("adminPassword"));
                                return nativeReply(nativeTarget(state, ""));
                            }
                            if (!Set.of("install", "start", "resume", "prepare", "initialize").contains(action))
                                throw new AssertionError("Unexpected native action: " + action);
                            var plan = DeploymentDatabasePlanActionLog.find(context.tasks(), task.get())
                                .orElseThrow();
                            var key = plan.facts().keys().getFirst();
                            var facts = DeploymentNativeDatabaseActionLog.find(context.tasks(), task.get(),
                                DeploymentNativeDatabaseActionLog.KEY + "." + key).orElseThrow().facts();
                            assertEquals("DISPATCHED", facts.phase());
                            assertEquals(action, facts.action());
                            assertEquals(frame.digest, facts.requestDigest());
                            byte[] password = frame.sensitive.get("applicationPassword");
                            if (Set.of("prepare", "initialize").contains(action)) {
                                assertNotNull(password);
                                assertTrue(password.length > 0);
                            }
                            String digest = Set.of("prepare", "initialize").contains(action)
                                ? DeploymentNativeDatabaseWire.digest(password)
                                : "";
                            boolean denied = action.equals("prepare") && state.authFailuresRemaining-- > 0;
                            boolean restoreRequired = action.equals("start") && state.restorePending;
                            boolean schemaChanged = action.equals("initialize") && state.schemaFailureRemaining > 0;
                            if (schemaChanged) {
                                assertEquals(state.sourceDigest, frame.fields.get("sourceSha256"));
                                assertArrayEquals(
                                    "CREATE TABLE sample(id integer);\n\n".getBytes(StandardCharsets.UTF_8),
                                    frame.sensitive.get("sql"));
                                assertEquals("false", frame.fields.get("existingApproved"));
                                assertFalse(state.initialized);
                                state.schemaFailureRemaining--;
                            }
                            if (action.equals("start") && state.install == NativeInstallType.START_UNKNOWN)
                                return unknown();
                            if (Set.of("prepare", "initialize").contains(action) && !denied) {
                                if (state.passwordDigest.isEmpty() || state.acceptRotation) {
                                    state.passwordDigest = digest;
                                    state.acceptRotation = false;
                                } else
                                    assertEquals(state.passwordDigest, digest);
                            }
                            if (action.equals("prepare") && !denied)
                                state.prepared = true;
                            if (action.equals("initialize") && !denied && !schemaChanged) {
                                assertEquals(state.sourceDigest, frame.fields.get("sourceSha256"));
                                assertEquals(state.install == NativeInstallType.EXISTING_SCHEMA_APPROVED
                                        || state.install == NativeInstallType.AUTH_SCHEMA_APPROVED
                                        ? "true"
                                        : "false",
                                    frame.fields.get("existingApproved"));
                                assertArrayEquals(
                                    "CREATE TABLE sample(id integer);\n\n".getBytes(StandardCharsets.UTF_8),
                                    frame.sensitive.get("sql"));
                                state.initialized = true;
                            }
                            if (action.equals("install")) {
                                assertEquals("postgresql-16", frame.fields.get("package"));
                                assertEquals("16.6", frame.fields.get("engineVersion"));
                                if (state.install == NativeInstallType.REPLACE) {
                                    assertEquals("true", frame.fields.get("replacementApproved"));
                                    assertEquals("server-publication", frame.fields.get("approvedServer"));
                                }
                                state.installed = true;
                                state.replaced = true;
                                if (state.install == NativeInstallType.REPLACE)
                                    state.restorePending = true;
                            }
                            if (action.equals("resume"))
                                state.restorePending = false;
                            var result = new LinkedHashMap<String, String>();
                            result.put("status",
                                denied
                                    ? "AUTH_REQUIRED"
                                    : schemaChanged
                                    ? "STATE_CHANGED"
                                    : restoreRequired ? "MANUAL_RESTORE_REQUIRED" : "OK");
                            result.put("receipt.formatVersion", "1");
                            result.put("receipt.operationId", facts.operationId().toString());
                            result.put("receipt.taskId", task.get().id().toString());
                            result.put("receipt.serverId", facts.serverId());
                            result.put("receipt.action", facts.action());
                            result.put("receipt.targetDigest", facts.targetDigest());
                            result.put("receipt.requestDigest", facts.requestDigest());
                            result.put("receipt.phase",
                                denied || schemaChanged || restoreRequired ? "FAILED" : "SUCCEEDED");
                            result.put("receipt.effectBoundary",
                                denied || schemaChanged
                                    ? "NONE"
                                    : restoreRequired ? "MAY_HAVE_RUN" : "CONFIRMED");
                            if (denied || schemaChanged || restoreRequired)
                                result.put("receipt.failureCode",
                                    denied
                                        ? "AUTH_REQUIRED"
                                        : schemaChanged ? "STATE_CHANGED" : "MANUAL_RESTORE_REQUIRED");
                            else if (action.equals("install"))
                                nativeInventory(state, "result.").forEach(result::put);
                            else if (Set.of("start", "resume").contains(action))
                                nativeInstance(state, "result.instance.").forEach(result::put);
                            else
                                nativeTarget(state, "result.").forEach(result::put);
                            return nativeReply(result);
                        }
                    }
                }
                try {
                    return method.invoke(existing, args);
                } catch (InvocationTargetException wrapped) {
                    throw wrapped.getCause();
                }
            });
    }

    /** One observed PostgreSQL inventory, including an exact package candidate. / 一份含精确软件包候选的 PostgreSQL 库存。
     * @param state controlled package state / 受控软件包状态
     * @param prefix helper result prefix / helper 结果前缀
     * @return strict non-secret helper fields / 严格非秘密 helper 字段 */
    private static Map<String, String> nativeInventory(NativeState state, String prefix) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("status", "OK");
        fields.put(prefix + "engine", "POSTGRESQL");
        fields.put(prefix + "instanceCount", state.installed ? "1" : "0");
        fields.put(prefix + "conflictCount", "0");
        if (state.installed)
            nativeInstance(state, prefix + "instance.0.").forEach(fields::put);
        if (state.install != NativeInstallType.NONE) {
            fields.put(prefix + "package", "postgresql-16");
            fields.put(prefix + "packageVersion", "16.6-1");
            fields.put(prefix + "engineVersion", "16.6");
        }
        return fields;
    }

    /** One exact instance projection for inventory and target responses. / 库存及目标回包的一份精确实例投影。
     * @param state controlled package state / 受控软件包状态
     * @param prefix protocol field prefix / 协议字段前缀
     * @return instance fields / 实例字段 */
    private static Map<String, String> nativeInstance(NativeState state, String prefix) {
        return Map.of(prefix + "engine", "POSTGRESQL", prefix + "id", "postgresql-16", prefix + "version",
            state.install == NativeInstallType.REPLACE && !state.replaced ? "15.6" : "16.6", prefix + "port",
            "5432", prefix + "service", "postgresql.service", prefix + "dataDirectory",
            "/var/lib/postgresql/16/main", prefix + "fingerprint", "a".repeat(64), prefix + "running",
            Boolean.toString(!state.restorePending));
    }

    /** One target whose ownership changes only after actual controlled helper actions. / 仅在真实受控 helper 动作后变化的目标。
     * @param state target state / 目标状态
     * @param prefix protocol field prefix / 协议字段前缀
     * @return exact target fields / 精确目标字段 */
    private static Map<String, String> nativeTarget(NativeState state, String prefix) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("status", "OK");
        nativeInstance(state, prefix + "instance.").forEach(fields::put);
        fields.put(prefix + "applicationId", state.applicationId);
        fields.put(prefix + "database", "sampledb");
        fields.put(prefix + "username", "sampleuser");
        fields.put(prefix + "exists", Boolean.toString(state.prepared));
        fields.put(prefix + "empty", Boolean.toString(!state.initialized));
        fields.put(prefix + "ownershipToken", state.prepared ? "b".repeat(64) : "");
        fields.put(prefix + "initialization", state.initialized ? "COMPLETE" : state.prepared ? "EMPTY" : "UNOWNED");
        fields.put(prefix + "initializedSourceSha256", state.initialized ? state.sourceDigest : "");
        return fields;
    }

    /** Encodes only non-secret helper facts using the real protocol field framing. / 用真实协议字段帧编码仅非秘密 helper 事实。
     * @param fields exact observed fields / 精确观察字段
     * @return successful controlled command / 成功受控命令 */
    private static OperationResult<CommandResult> nativeReply(Map<String, String> fields) {
        byte[] bytes = DeploymentNativeDatabaseWire.frame(fields);
        try {
            return reply(new String(bytes, StandardCharsets.US_ASCII));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
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
        return peer(scenario, actions, sha, bytes, path, candidate, callbackEnded, false);
    }

    private RemoteSession peer(ScenarioType scenario, List<String> actions, AtomicReference<String> sha,
                               AtomicLong bytes, AtomicReference<Path> path, AtomicReference<DeploymentRemoteWorkspace> candidate,
                               CountDownLatch callbackEnded, boolean noPortNode) {
        var configStages = new AtomicInteger();
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
            new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                if (method.getName().equals("facts"))
                    return new RemoteFacts("", "", CONTROLLED_FINGERPRINT);
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
                    if (path.get() == null) {
                        path.set(transfer.localPath());
                        bytes.set(Files.size(transfer.localPath()));
                    }
                    assertEquals(path.get(), transfer.localPath());
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new TransferResult(bytes.get(),
                        sha.get(), transfer.localPath(), transfer.remoteAbsolutePath())), Optional.empty());
                }
                assertEquals("execute", method.getName());
                var command = (CommandRequest) args[0];
                String script = command.script();
                if (script.contains("managed-helper' probe")) {
                    actions.add("helper-probe");
                    return reply("HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION
                        + "\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
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
                    if (noPortNode)
                        return reply(
                            "TOOLCHAIN=NODE|22.14.0|/usr/local/lib/windowstolinux/toolchains/versions/node-"
                                + "b".repeat(64) + "|MANAGED|https://official.example/node|"
                                + "b".repeat(64) + "\n");
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
                        + "\nUID=1001\nENGINE=ordinary\nTOOL=" + (noPortNode ? "NPM" : "GO_MODULE")
                        + "\nCOMPAT=0\nLIMIT=-\nCONFLICT=0\nHEAD=\nTAIL=\n");
                }
                if (script.contains("stage-config")) {
                    actions.add("stage-config");
                    return scenario == ScenarioType.SECOND_CONFIG_UNKNOWN && configStages.incrementAndGet() == 2
                        ? unknown()
                        : reply("INPUT_STAGED=1\n");
                }
                if (script.contains("stage-secret")) {
                    actions.add("stage-secret");
                    if (script.contains("page-application")) {
                        assertTrue(script.contains("'page-application' '1'"),
                            "The original business revision must be sent unchanged");
                        byte[] submitted = command.stdin();
                        try {
                            assertArrayEquals(new byte[]{(byte) 0xe4, (byte) 0xbd, (byte) 0xa0, (byte) 0xe5,
                                (byte) 0xa5, (byte) 0xbd, 0x21}, submitted);
                        } finally {
                            Arrays.fill(submitted, (byte) 0);
                        }
                        actions.add("stage-secret-page-application");
                    }
                    return reply("INPUT_STAGED=1\n");
                }
                if (script.contains("snapshot-deployment")) {
                    actions.add("snapshot");
                    var taskToken = java.util.regex.Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                        .matcher(script);
                    assertTrue(taskToken.find(), script);
                    return reply(scenario == ScenarioType.HEALTH_FAILED_ROLLBACK_UNSUPPORTED_MODE
                        ? "SNAPSHOT_TOKEN=" + taskToken.group()
                        + "\nPREVIOUS=1\nPREVIOUS_RUNNING=0\nPREVIOUS_DIGEST=" + "d".repeat(64)
                        + "\nPREVIOUS_KIND=deployment\n"
                        : "PREVIOUS=0\n");
                }
                if (script.contains("publish-deployment")) {
                    if (noPortNode) {
                        var wire = java.util.regex.Pattern
                            .compile("'publish-deployment' 'service' '([a-z0-9-]+)' '([0-9a-f]{64})'")
                            .matcher(script);
                        assertTrue(wire.find(), script);
                        assertEquals(candidate.get().candidateId(), wire.group(1));
                        actions.add("wire-release=" + wire.group(2));
                    }
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
                    if (noPortNode) {
                        var quoted = java.util.regex.Pattern.compile("'([^']*)'").matcher(script);
                        String payload = null;
                        while (quoted.find())
                            payload = quoted.group(1);
                        assertNotNull(payload, script);
                        var fields = new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8)
                            .split("\\x00", -1);
                        assertEquals(5, fields.length);
                        assertEquals("health-v1", fields[0]);
                        assertEquals("PROCESS", fields[1]);
                        assertTrue(Integer.parseInt(fields[2]) > 0);
                        assertTrue(Integer.parseInt(fields[3]) >= 0);
                        assertEquals("", fields[4]);
                    }
                    actions.add("health");
                    return scenario == ScenarioType.HEALTH_FAILED_ROLLBACK
                        || scenario == ScenarioType.HEALTH_FAILED_ROLLBACK_UNSUPPORTED_MODE
                        || scenario == ScenarioType.HEALTH_FAILED_ROLLBACK_CLOSE_UNKNOWN
                        || scenario == ScenarioType.ROLLBACK_UNKNOWN
                        ? reply(1, "HEALTHY=0\n")
                        : reply("HEALTHY=1\n");
                }
                if (script.contains("inspect-runtime")) {
                    actions.add("inspect-runtime");
                    return reply(scenario == ScenarioType.HEALTH_FAILED_ROLLBACK_UNSUPPORTED_MODE
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
        return new OperationResult<>(TaskResult.SUCCEEDED,
            Optional.of(new CommandResult(Optional.of(exit), output, "", false)), Optional.empty());
    }

    /** Loses the original remote acknowledgement without inventing a retry. / 丢失原远端回执而不补造重试。
     * @return uncertain result / 未知结果 */
    private static OperationResult<CommandResult> unknown() {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            "FIXTURE_PUBLICATION_ACK_LOST", "Controlled receipt was lost", Map.of(), Optional.empty())));
    }
}
