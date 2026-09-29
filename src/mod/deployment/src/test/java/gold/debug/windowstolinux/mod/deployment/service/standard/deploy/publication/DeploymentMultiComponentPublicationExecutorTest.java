package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.SecretRef;
import gold.debug.windowstolinux.appapi.server.ReferenceResolution;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentConfigurationRepository;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentGraphRepository;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSecretRepository;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentWorkloadArguments;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationScopeType;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentNativeDatabasePreparation;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticWorkflow;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectComponent;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.plan.ReviewedDeploymentPlanner;
import gold.debug.windowstolinux.mod.deployment.source.*;
import gold.debug.windowstolinux.mod.deployment.source.git.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Runs a real original graph task, frozen source and H2 against one reviewed controlled SSH session. / 针对同一获审阅受控SSH会话运行真实原图任务、冻结来源和H2。 */
class DeploymentMultiComponentPublicationExecutorTest extends DeploymentRuntimeFixture {
    /** Confirmed publication and confirmed middle-component health failure. / 已确认发布与已确认中途组件健康失败。 */
    private enum ScenarioType {
        SUCCESS, HEALTH_FAILED_ROLLBACK, MIDDLE_HEALTH_FAILED_ROLLBACK, PUBLISH_UNKNOWN, ROLLBACK_KNOWN_FAILURE, ORIGINAL_CLEANUP_REFUSED, STAGE_UNKNOWN, CLOSE_UNKNOWN, TOO_MANY, DUPLICATE_ORDER, ROLLBACK_CLEANUP_REFUSED, CANCELLED_BEFORE_SOURCE, TOTAL_SPACE_EXCEEDED, PURPOSE_UNKNOWN, PURPOSE_MISMATCH
    }

    /** Original grants must pass to the same remote session. / 原授权必须传给同一远端会话。 */
    private final Map<ApprovalGrant, ActionIntent> grants = new ConcurrentHashMap<>();

    /** Optional actual loopback SSH connection retained by the original task before its child exits. / 原任务子进程退出前可选保留的真实回环SSH连接。 */
    private int recoveryPort = 22;

    /** One original cleanup review refused before dispatch. / 一次原清理在发送前被拒绝。 */
    private final AtomicBoolean refuseOriginalCleanup = new AtomicBoolean();

    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

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
                return "Graph upload";
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancellationToken cancellation) {
                assertEquals(List.of("server-publication"), intent.serverIds());
                if (intent.semantics().map(ActionSemantics::operation)
                        .filter("deployment.publication.candidate-cleanup.v1"::equals).isPresent()
                        && refuseOriginalCleanup.compareAndSet(true, false))
                    return false;
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

    /** All builds, stages and snapshots precede topology publication and one atomic business commit. / 所有构建、封存和快照先于拓扑发布及一次原子业务提交。
     * @throws Exception if the real original task fails / 真实原任务失败时 */
    @ParameterizedTest
    @EnumSource(value = ScenarioType.class, names = {"SUCCESS", "HEALTH_FAILED_ROLLBACK",
            "MIDDLE_HEALTH_FAILED_ROLLBACK"})
    void wholeGraphPublishesUnderOneOriginalTaskAndSession(ScenarioType scenario) throws Exception {
        runScenario(scenario, false);
    }

    /** Uses the page workflow's separate child archives for one real graph task and one controlled session. / 使用页面流程的独立组件归档执行一项真实整图任务及一条受控会话。
     * @throws Exception if original task preparation or publication fails / 原任务准备或发布失败时 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void staticWorkflowPublishesDiscoveredGraphFromSeparateArchiveRoots(boolean git) throws Exception {
        runStaticGraph(git, false);
    }

    /** Checks DEFAULT SQLite path and resource identity under the final graph namespace. / 核对最终整图命名空间下的默认 SQLite 路径及资源身份。
     * @throws Exception if original graph publication fails / 原整图发布失败时 */
    @Test
    void staticWorkflowPublishesGraphWithIsolatedSqliteComponent() throws Exception {
        runStaticGraph(false, true);
    }

    /** Publishes mixed SQLite/native facts in one graph component after whole-source local validation. / 整份源码本机校验后在一图组件发布 SQLite 与原生合并事实。
     * @throws Exception if original CLASS/H2 graph publication fails / 原 CLASS/H2 整图发布失败时 */
    @Test
    void staticWorkflowPublishesGraphWithMixedNativeAndSqliteReview() throws Exception {
        runStaticGraph(false, false, true);
    }

    /** A later component's missing SQL blocks the entire graph before helper, DB or secret effects. / 后续组件缺失 SQL 在整图 helper、数据库和秘密效果前阻断。
     * @throws Exception if original source validation fails unexpectedly / 原来源校验意外失败时 */
    @Test
    void laterInvalidGraphSqlCannotStartEarlierNativePreparation() throws Exception {
        runStaticGraph(false, false, true, true);
    }

    /** Executes the shared original graph fixture with optional source-derived SQLite. / 使用可选源码派生 SQLite 执行原整图夹具。
     * @param git use local Git / 使用本机 Git
     * @param sqlite declare SQLite on the API component / 在 API 组件声明 SQLite
     * @throws Exception if original graph publication fails / 原整图发布失败时 */
    private void runStaticGraph(boolean git, boolean sqlite) throws Exception {
        runStaticGraph(git, sqlite, false);
    }

    /** Runs the same graph with optional mixed native and SQLite declarations. / 使用可选原生与 SQLite 混合声明运行同一整图。
     * @param git use local Git / 使用本机 Git
     * @param sqlite declare only SQLite / 仅声明 SQLite
     * @param mixed declare both native and SQLite / 同时声明原生与 SQLite
     * @throws Exception if original graph fails / 原整图失败时 */
    private void runStaticGraph(boolean git, boolean sqlite, boolean mixed) throws Exception {
        runStaticGraph(git, sqlite, mixed, false);
    }

    /** Runs one graph with an optional deliberately invalid later component. / 使用可选刻意无效后续组件运行一图。
     * @param git local Git selection / 本机 Git 选择
     * @param sqlite SQLite-only API declaration / API 仅 SQLite 声明
     * @param mixed API mixed native and SQLite declaration / API 原生和 SQLite 混合声明
     * @param invalidLater missing Web SQL source / Web SQL 来源缺失
     * @throws Exception if original task setup fails / 原任务准备失败时 */
    private void runStaticGraph(boolean git, boolean sqlite, boolean mixed, boolean invalidLater) throws Exception {
        for (String id : List.of("api", "web")) {
            var source = selectedSourceDirectory().resolve(id);
            Files.createDirectories(source);
            Files.writeString(source.resolve("go.mod"), "module example.test/" + id + "\n\ngo 1.24\n");
            Files.writeString(source.resolve("go.sum"), "example.test/dependency v1 h1:fixture\n");
            Files.writeString(source.resolve("main.go"), "package main\nfunc main() {}\n");
            if (sqlite && id.equals("api")) {
                Files.writeString(source.resolve("windowstolinux-db.properties"),
                        "db=main\ndb.main.engine=SQLITE\ndb.main.path=DEFAULT\n"
                                + "db.main.pathEnvironment=API_DB_PATH\ndb.main.initialize=schema.sql\n");
                Files.writeString(source.resolve("schema.sql"), "CREATE TABLE sample(id integer);\n");
            }
            if (mixed && id.equals("api")) {
                Files.writeString(source.resolve("windowstolinux-db.properties"),
                        "db=main,local\ndb.main.engine=POSTGRESQL\ndb.main.version=>=16\n"
                                + "db.main.database=sampledb\ndb.main.username=sampleuser\n"
                                + "db.main.environmentPrefix=DB\ndb.main.passwordEnvironment=DB_PASSWORD\n"
                                + "db.main.initialize=schema.sql\ndb.local.engine=SQLITE\n"
                                + "db.local.path=DEFAULT\ndb.local.pathEnvironment=API_DB_PATH\n"
                                + "db.local.initialize=local.sql\n");
                Files.writeString(source.resolve("schema.sql"), "CREATE TABLE sample(id integer);\n");
                Files.writeString(source.resolve("local.sql"), "CREATE TABLE local(id integer);\n");
            }
            if (invalidLater && id.equals("web"))
                Files.writeString(source.resolve("windowstolinux-db.properties"),
                        "db=local\ndb.local.engine=SQLITE\ndb.local.path=DEFAULT\n"
                                + "db.local.pathEnvironment=WEB_DB_PATH\n" + "db.local.initialize=missing.sql\n");
        }
        String commit = null;
        if (git) {
            git(selectedSourceDirectory(), "init", "-b", "main");
            git(selectedSourceDirectory(), "config", "user.email", "test@example.invalid");
            git(selectedSourceDirectory(), "config", "user.name", "Test");
            git(selectedSourceDirectory(), "add", ".");
            git(selectedSourceDirectory(), "commit", "-m", "graph");
            commit = git(selectedSourceDirectory(), "rev-parse", "HEAD");
        }
        final String pinnedCommit = commit;
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = new ServerRef("server-publication");
            runtime.servers().saveServerProfile(
                    ServerProfile.fromStored(
                            new StoredServerProfile(server.id(), "127.0.0.1", 22, "root", "fixture-password")),
                    "fixture-password".toCharArray());
            var actions = new CopyOnWriteArrayList<String>();
            var callbackEnded = new CountDownLatch(1);
            var existing = peer(ScenarioType.SUCCESS, actions, new AtomicReference<>(), new AtomicLong(),
                    new AtomicReference<>(), callbackEnded);
            var nativeState = new DeploymentStandardPublicationExecutorTest.NativeState();
            var reviewedTask = new AtomicReference<TaskContext>();
            record Uploaded(long bytes, String sha) {
            }
            var uploaded = new ConcurrentHashMap<String, Uploaded>();
            RemoteSession session = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                    new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                        if (method.getName().equals("upload")) {
                            actions.add("upload");
                            var request = (TransferRequest) args[0];
                            var match = java.util.regex.Pattern.compile("/work/([a-z0-9-]+)/")
                                    .matcher(request.remoteAbsolutePath());
                            assertTrue(match.find(), request.remoteAbsolutePath());
                            byte[] content = Files.readAllBytes(request.localPath());
                            String digest = java.util.HexFormat.of()
                                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
                            uploaded.put(match.group(1), new Uploaded(content.length, digest));
                            return new OperationResult<>(Outcome.SUCCEEDED,
                                    Optional.of(new TransferResult(content.length, digest, request.localPath(),
                                            request.remoteAbsolutePath())),
                                    Optional.empty());
                        }
                        if (method.getName().equals("execute")) {
                            String script = ((CommandRequest) args[0]).script();
                            if (script.contains("candidate-create-task")) {
                                actions.add("create-candidate");
                                var match = java.util.regex.Pattern.compile("candidate-create-task '[^']+' '([^']+)'")
                                        .matcher(script);
                                assertTrue(match.find(), script);
                                return reply("CANDIDATE=/var/lib/windowstolinux/work/" + match.group(1) + "\n");
                            }
                            if (script.contains("stat -c '%s'")) {
                                actions.add("verify-archive");
                                var match = java.util.regex.Pattern.compile("/work/([a-z0-9-]+)/").matcher(script);
                                assertTrue(match.find(), script);
                                var actual = uploaded.get(match.group(1));
                                assertNotNull(actual, script);
                                return reply("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\nBYTES=" + actual.bytes()
                                        + "\nSHA256=" + actual.sha() + "\nOWNER=0\n");
                            }
                        }
                        try {
                            return method.invoke(existing, args);
                        } catch (InvocationTargetException wrapped) {
                            throw wrapped.getCause();
                        }
                    });
            var real = runtime.modContexts().get(OWNER).context();
            var combined = mixed
                    ? DeploymentStandardPublicationExecutorTest.nativePeer(session, real, reviewedTask, nativeState,
                            actions, grants)
                    : session;
            var bound = withSession(real, combined);
            var workflow = new DeploymentStaticWorkflow(bound, new DeploymentReferenceFlow(bound));
            var originalTask = new AtomicReference<UUID>();
            var archives = new AtomicReference<Map<String, String>>();
            var reviewer = new DeploymentStaticWorkflow.Reviewer() {
                public OperationResult<Map<String, String>> databaseInputs(ServerRef exact, String componentId,
                        String manifest, Path frozenRoot, String applicationId,
                        gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.db.DatabaseProjectInspector.Assessment assessment,
                        TaskContext task) throws Exception {
                    assertTrue(sqlite || mixed);
                    assertEquals(invalidLater && componentId.equals("web") ? "web" : "api", componentId);
                    assertEquals("whole-app-" + componentId, applicationId);
                    assertEquals(originalTask.get(), task.id());
                    if (invalidLater && componentId.equals("web"))
                        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(Map.of()), Optional.empty());
                    if (mixed) {
                        nativeState.applicationId = applicationId;
                        nativeState.sourceDigest = DeploymentNativeDatabasePreparation
                                .local(componentId, applicationId, manifest, frozenRoot, assessment, task)
                                .sourceDigest();
                    }
                    return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(Map.of()), Optional.empty());
                }

                public OperationResult<Boolean> confirmSqliteInitialization(ServerRef exact, String componentId,
                        String manifest,
                        gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.db.DatabaseProjectInspector.Assessment assessment,
                        TaskContext task) {
                    assertEquals("api", componentId);
                    assertEquals(List.of(mixed ? "local.sql" : "schema.sql"),
                            assessment.databases().getFirst().initializationFiles());
                    return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(true), Optional.empty());
                }

                public OperationResult<DeploymentStaticWorkflow.Selection> select(ServerRef exact, String manifest,
                        List<DiscoveredProjectComponent> discovered, TaskContext task) {
                    assertEquals(2, discovered.size());
                    originalTask.set(task.id());
                    reviewedTask.set(task);
                    var choices = new HashMap<String, DeploymentStaticWorkflow.Choice>();
                    for (var component : discovered)
                        choices.put(component.id(),
                                new DeploymentStaticWorkflow.Choice(DeploymentProjectKind.GO_SERVICE,
                                        component.id().equals("web") ? Set.of("api") : Set.of()));
                    return new OperationResult<>(Outcome.SUCCEEDED,
                            Optional.of(
                                    new DeploymentStaticWorkflow.Selection("whole-app", choices, Optional.of("web"))),
                            Optional.empty());
                }

                public OperationResult<Map<String, DeploymentStaticWorkflow.Answers>> review(ServerRef exact,
                        String manifest, List<DeploymentStaticWorkflow.Prompt> prompts, TaskContext task) {
                    assertEquals(originalTask.get(), task.id());
                    var reviewed = new HashMap<String, DeploymentStaticWorkflow.Answers>();
                    for (var prompt : prompts) {
                        var values = new HashMap<>(prompt.defaults());
                        values.put("primary", prompt.componentId());
                        values.put("secondary", "main.go");
                        values.put("version", "1.24");
                        values.put("healthMode", "TCP");
                        values.put("port", prompt.componentId().equals("api") ? "8081" : "8082");
                        values.put("exposure", "INTERNAL");
                        var answer = new DeploymentStaticWorkflow.Answers(values, List.of(),
                                DeploymentBuildLimits.defaultNonRoot(), false, true);
                        workflow.validateReview(prompt, answer);
                        reviewed.put(prompt.componentId(), answer);
                    }
                    return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(Map.copyOf(reviewed)),
                            Optional.empty());
                }

                public OperationResult<Boolean> confirm(ServerRef exact, String manifest, Map<String, String> digests,
                        Map<String, DeploymentStaticWorkflow.Answers> answers,
                        Map<String, gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentPreparedDatabase> databases,
                        Optional<DeploymentStaticWorkflow.GitReview> git, TaskContext task) {
                    assertEquals(originalTask.get(), task.id());
                    if (sqlite || mixed) {
                        assertTrue(databases.get("web").bindings().isEmpty());
                        var prepared = databases.get("api");
                        var binding = prepared.bindings().stream()
                                .filter(value -> value.connection() instanceof DeploymentDatabaseConnection.Sqlite)
                                .findFirst().orElseThrow();
                        var connection = (DeploymentDatabaseConnection.Sqlite) binding.connection();
                        String configured = prepared.configuration().stream()
                                .filter(value -> value.key().equals("API_DB_PATH")).findFirst().orElseThrow().value()
                                .canonicalValue();
                        assertEquals(connection.physicalPath("whole-app-api", binding.databaseId()), configured);
                        assertTrue(prepared.schemaReview().orElseThrow().firstDeploymentInitializationPlanned());
                        if (mixed) {
                            assertEquals(2, prepared.bindings().size());
                            assertEquals(Set.of("schema.sql", "local.sql"),
                                    prepared.schemaReview().orElseThrow().initializedSqlPaths());
                            assertEquals(nativeState.sourceDigest,
                                    prepared.schemaReview().orElseThrow().inspectionSha256());
                            assertEquals(1, prepared.secrets().size());
                        }
                    }
                    assertEquals(pinnedCommit != null, git.isPresent());
                    git.ifPresent(value -> {
                        assertEquals(selectedSourceDirectory().toUri().toString(), value.remote());
                        assertEquals("DEFAULT:HEAD", value.reference());
                        assertEquals(pinnedCommit, value.commit());
                    });
                    archives.set(Map.copyOf(digests));
                    assertEquals(2, new HashSet<>(digests.values()).size());
                    return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(true), Optional.empty());
                }
            };
            var gitInput = git
                    ? Optional
                            .of(new DeploymentStaticWorkflow.GitSource(
                                    new DeploymentGitRequest(new DeploymentGitRemote(selectedSourceDirectory().toUri()),
                                            new DeploymentGitReference.DefaultBranch(), Set.of(), 65536, true),
                                    locateGit()))
                    : Optional.<DeploymentStaticWorkflow.GitSource>empty();
            var handle = workflow.submit(server,
                    new DeploymentStaticWorkflow.Draft(git ? "reviewed-git-graph" : "", 65536, 100, 65536), gitInput,
                    reviewer);
            if (invalidLater) {
                var rejected = handle.completion().toCompletableFuture().get(25, TimeUnit.SECONDS);
                assertEquals(Outcome.FAILED, rejected.outcome(), rejected.toString());
                assertTrue(actions.stream().noneMatch(value -> value.startsWith("native-")
                        || value.equals("helper-probe") || value.equals("stage-secret")), actions.toString());
                assertEquals(0, countMod(runtime, "deployment_release"));
                assertEquals(0,
                        scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                return;
            }
            assertTrue(callbackEnded.await(30, TimeUnit.SECONDS), () -> handle.snapshot() + " actions=" + actions);
            OperationResult<Void> result;
            try {
                result = handle.completion().toCompletableFuture().get(12, TimeUnit.SECONDS);
            } catch (TimeoutException unfinished) {
                throw new AssertionError(
                        "Static graph unfinished: " + handle.snapshot() + ", actions=" + actions + ", uploads="
                                + uploaded + ", resources="
                                + scalar(runtime,
                                        "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"),
                        unfinished);
            }
            assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
            assertEquals(2, archives.get().size());
            assertEquals(2, uploaded.size());
            assertEquals(2, actions.stream().filter("build"::equals).count());
            assertTrue(actions.lastIndexOf("build") < actions.indexOf("publish"), actions.toString());
            assertEquals(2, actions.stream().filter("publish"::equals).count());
            assertEquals(1, countMod(runtime, "deployment_release"));
            assertEquals(2, countMod(runtime, "deployment_unit_release_binding"));
            if (sqlite || mixed) {
                var inspected = real.tasks().submit(new TaskRequest("sqlite-graph-inspect",
                        "Inspect published SQLite graph", List.of(server.id()), false), task -> {
                            var graph = new DeploymentGraphRepository(real.db()).find(task, "whole-app").orElseThrow();
                            var api = graph.components().stream().filter(value -> value.componentId().equals("api"))
                                    .findFirst().orElseThrow();
                            var web = graph.components().stream().filter(value -> value.componentId().equals("web"))
                                    .findFirst().orElseThrow();
                            assertEquals(List.of(), web.resources().databaseBindings().orElseThrow());
                            var bindings = api.resources().databaseBindings().orElseThrow();
                            var binding = bindings.stream()
                                    .filter(value -> value.connection() instanceof DeploymentDatabaseConnection.Sqlite)
                                    .findFirst().orElseThrow();
                            assertEquals(mixed ? "local" : "main", binding.databaseId());
                            var connection = (DeploymentDatabaseConnection.Sqlite) binding.connection();
                            assertEquals(List.of(mixed ? "local.sql" : "schema.sql"), connection.initializationFiles());
                            if (mixed) {
                                assertEquals(2, bindings.size());
                                var nativeBinding = bindings.stream().filter(
                                        value -> value.connection() instanceof DeploymentDatabaseConnection.Server)
                                        .findFirst().orElseThrow();
                                assertEquals("main", nativeBinding.databaseId());
                                assertEquals("sampledb",
                                        ((DeploymentDatabaseConnection.Server) nativeBinding.connection()).database());
                            }
                            var configuration = new DeploymentConfigurationRepository(real.db())
                                    .find(task, "whole-app-api", 1).orElseThrow();
                            assertEquals(connection.physicalPath("whole-app-api", binding.databaseId()),
                                    configuration.requireValue("API_DB_PATH", DeploymentConfigurationScopeType.RUNTIME)
                                            .canonicalValue());
                            if (mixed)
                                assertEquals("127.0.0.1",
                                        configuration.requireValue("DB_HOST", DeploymentConfigurationScopeType.RUNTIME)
                                                .canonicalValue());
                            return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                        });
                assertEquals(Outcome.SUCCEEDED,
                        inspected.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            }
            if (git) {
                assertEquals(1, countMod(runtime, "deployment_project"));
                try (var access = scope(runtime, true);
                        var connection = access.openConnection();
                        var statement = connection.createStatement();
                        var rows = statement.executeQuery("SELECT p.source_location,p.git_ref,p.git_commit "
                                + "FROM deployment_execution e JOIN deployment_project p ON e.project_id=p.project_id")) {
                    assertTrue(rows.next());
                    assertEquals(selectedSourceDirectory().toUri().toString(), rows.getString(1));
                    assertEquals("DEFAULT:HEAD", rows.getString(2));
                    assertEquals(pinnedCommit, rows.getString(3));
                    assertFalse(rows.next());
                }
            }
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    private static Path locateGit() {
        for (String directory : System.getenv("PATH").split(java.io.File.pathSeparator)) {
            var path = Path.of(directory).resolve("git.exe");
            if (Files.isRegularFile(path))
                return path.toAbsolutePath().normalize();
        }
        throw new AssertionError("Local native Git is required");
    }

    private static String git(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add(locateGit().toString());
        command.addAll(List.of(arguments));
        var child = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        child.getOutputStream().close();
        var output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, child.waitFor(), output);
        return output;
    }

    /** Isolates a genuine original UNKNOWN occupation from the parent test JVM. / 将真实原UNKNOWN占用隔离于父测试JVM。
     * @throws Exception if the controlled child cannot finish / 受控子进程无法完成时 */
    @Test
    void unknownPublishKeepsWholeGraphAndNeverResendsRollbackOrCleanup() throws Exception {
        runIsolated(ScenarioType.PUBLISH_UNKNOWN);
    }

    /** A fully observed failed rollback continues other inverse restores while retaining manual review. / 完整观察到的回滚失败仍继续其他逆序恢复，同时保留人工核对。
     * @throws Exception if the controlled child cannot finish / 受控子进程无法完成时 */
    @Test
    void knownRollbackFailureStillRestoresOtherComponents() throws Exception {
        runIsolated(ScenarioType.ROLLBACK_KNOWN_FAILURE);
    }

    /** Original-session approval refusal leaves its known result and retryable prior stage. / 原会话审核拒绝保留已知结果及可重审前一阶段。
     * @throws Exception if the controlled child fails / 受控子进程失败时 */
    @Test
    void originalCleanupApprovalRefusalKeepsKnownPublication() throws Exception {
        runIsolated(ScenarioType.ORIGINAL_CLEANUP_REFUSED);
    }

    /** Rejects excessive or repeated graph execution indexes before any remote effect. / 任一远端副作用前拒绝过量或重复整图执行索引。
     * @param scenario malformed public plan / 畸形公开计划
     * @throws Exception if the original task fails / 原任务失败时 */
    @ParameterizedTest
    @EnumSource(value = ScenarioType.class, names = {"TOO_MANY", "DUPLICATE_ORDER"})
    void invalidGraphCannotCreateAnyCandidate(ScenarioType scenario) throws Exception {
        runScenario(scenario, false);
    }

    /** A cancelled original preparation never creates a candidate or a replacement task. / 原准备取消不创建候选或替代任务。
     * @throws Exception if the original task fails / 原任务失败时 */
    @Test
    void cancelledPreparationStopsBeforeCandidate() throws Exception {
        runScenario(ScenarioType.CANCELLED_BEFORE_SOURCE, false);
    }

    /** Two individually admissible workspaces exceed one original graph's target capacity. / 各自可准入的两份工作区合计超过同一原图目标容量。
     * @throws Exception if original preflight fails / 原预检失败时 */
    @Test
    void summedComponentSpaceIsRejectedBeforeAnyUpload() throws Exception {
        runScenario(ScenarioType.TOTAL_SPACE_EXCEEDED, false);
    }

    /** Requires the original saved purpose for a whole graph before any remote effect. / 整图任一远端副作用前要求原已保存用途。
     * @param scenario missing or mismatched purpose / 缺失或不匹配的用途
     * @throws Exception if actual task setup fails / 真实任务设置失败时 */
    @ParameterizedTest
    @EnumSource(value = ScenarioType.class, names = {"PURPOSE_UNKNOWN", "PURPOSE_MISMATCH"})
    void savedPurposeIsRequiredForWholeGraph(ScenarioType scenario) throws Exception {
        runScenario(scenario, false);
    }

    /** Runs only an occupied original task in a child that can exit without claiming cleanup. / 仅在可退出且不冒称清理的子进程运行占用中的原任务。
     * @param scenario exact uncertain outcome / 精确未决结果
     * @throws Exception if the child fails / 子进程失败时 */
    private void runIsolated(ScenarioType scenario) throws Exception {
        var javaBinary = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        var process = new ProcessBuilder(javaBinary.toString(), "-cp", System.getProperty("java.class.path"),
                getClass().getName(), "--unknown", root.toString(), scenario.name()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(50, TimeUnit.SECONDS), "Isolated graph task timed out");
            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("GRAPH_UNKNOWN_VERIFIED"), output);
        } finally {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Task-started child did not exit");
                }
            }
        }
    }

    /** Executes the original UNKNOWN case only in the test-created child JVM. / 仅在测试创建的子JVM执行原UNKNOWN场景。
     * @param args fixed child marker and target directory / 固定子进程标记及目标目录 */
    public static void main(String[] args) {
        if ((args.length != 3 && args.length != 4) || !args[0].equals("--unknown"))
            throw new IllegalArgumentException("Isolated graph probe only");
        try {
            var probe = new DeploymentMultiComponentPublicationExecutorTest();
            probe.root = Path.of(args[1]);
            if (args.length == 4)
                probe.recoveryPort = Integer.parseInt(args[3]);
            probe.runScenario(ScenarioType.valueOf(args[2]), true);
            System.out.println("GRAPH_UNKNOWN_VERIFIED");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    /** Runs one scenario against a real original task. / 针对真实原任务运行一项场景。
     * @param scenario controlled effect / 受控副作用
     * @param interruptedHost whether the child intentionally exits without normal host close / 子进程是否有意不正常关闭宿主
     * @throws Exception if the actual fixture fails / 实际夹具失败时 */
    private void runScenario(ScenarioType scenario, boolean interruptedHost) throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("go.mod"), "module example.test/service\n\ngo 1.24\n");
        Files.writeString(selectedSourceDirectory().resolve("go.sum"), "example.test/dependency v1 h1:fixture\n");
        Files.writeString(selectedSourceDirectory().resolve("main.go"), "package main\nfunc main() {}\n");
        refuseOriginalCleanup.set(
                scenario == ScenarioType.ORIGINAL_CLEANUP_REFUSED || scenario == ScenarioType.ROLLBACK_CLEANUP_REFUSED);
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
            var callbackEnded = new CountDownLatch(1);
            var peer = peer(scenario, actions, archiveSha, archiveBytes, archivePath, callbackEnded);
            var real = runtime.modContexts().get(OWNER).context();
            var bound = withSession(real, peer);
            var executor = new DeploymentMultiComponentPublicationExecutor(bound, new DeploymentReferenceFlow(bound));
            var reference = new DeploymentSecretReference("password-id", 1);
            boolean purposeCase = scenario == ScenarioType.PURPOSE_UNKNOWN || scenario == ScenarioType.PURPOSE_MISMATCH;
            var secret = purposeCase ? purposeSecret(real, reference, scenario == ScenarioType.PURPOSE_UNKNOWN) : null;
            var reviewedSecret = purposeCase && scenario == ScenarioType.PURPOSE_MISMATCH
                    ? new SecretRef(secret.id(), "deployment.wrong")
                    : secret;
            var credentials = purposeCase
                    ? Map.of(reference, reviewedSecret)
                    : Map.<DeploymentSecretReference, SecretRef>of();
            var ids = scenario == ScenarioType.MIDDLE_HEALTH_FAILED_ROLLBACK
                    ? List.of("db", "api", "web")
                    : scenario == ScenarioType.TOO_MANY
                            ? java.util.stream.IntStream.range(0, 17).mapToObj(index -> "unit" + index).toList()
                            : List.of("api", "web");
            boolean invalid = scenario == ScenarioType.TOO_MANY || scenario == ScenarioType.DUPLICATE_ORDER;
            String wholeHealthOwner = scenario == ScenarioType.TOO_MANY
                    ? ids.getLast()
                    : scenario == ScenarioType.DUPLICATE_ORDER ? "api" : "web";
            var units = new LinkedHashMap<String, DeploymentUnitIdentity>();
            for (var id : ids)
                units.put(id, DeploymentUnitIdentity.forManaged(id, server,
                        (id.equals("db") ? "a" : id.equals("api") ? "b" : "c").repeat(64)));
            var task = executor.submit(server, Optional.empty(), units, credentials, wholeHealthOwner,
                    new DeploymentHealthCheck.Tcp(8099, 10, 2), original -> {
                        if (scenario == ScenarioType.CANCELLED_BEFORE_SOURCE)
                            return new OperationResult<>(Outcome.CANCELLED, Optional.empty(),
                                    Optional.of(
                                            new Problem(ProblemCode.CANCELLED, "FIXTURE_GRAPH_PREPARATION_CANCELLED",
                                                    "Original preparation cancelled", Map.of(), Optional.empty())));
                        if (recoveryPort != 22)
                            real.linux().connect(server, original);
                        var selection = real.source().selectDirectory("Source", original);
                        var material = new DeploymentSourcePreparer(real.source()).prepare(selection, 65536, 100,
                                original);
                        var archive = new DeploymentSourceArchiver(real.source()).prepare(material, 65536, original);
                        archiveSha.set(archive.sha256());
                        archiveBytes.set(archive.byteCount());
                        archivePath.set(archive.path());
                        var analysis = new DeploymentStandardAnalyzer().analyze(material, "service",
                                DeploymentProjectKind.GO_SERVICE, Optional.empty(), false, original);
                        var originalFacts = analysis.value().orElseThrow().assessment().facts().orElseThrow();
                        var plans = new LinkedHashMap<String, ReviewedDeploymentPlan>();
                        for (var id : ids) {
                            DeploymentProjectFacts facts = new DeploymentProjectFacts(originalFacts.sourceRoot(), id,
                                    originalFacts.projectType(), originalFacts.buildTool(), originalFacts.support(),
                                    originalFacts.languageFacts(), originalFacts.evidence(), originalFacts.conflicts(),
                                    originalFacts.missingInformation(), originalFacts.toolchainRequirements(),
                                    originalFacts.buildDirectory());
                            var request = new ReviewedDeploymentRequest(server, facts, material, archive,
                                    DeploymentConfigurationSnapshot.create(id, 1, "v1", Instant.now(), List.of()),
                                    purposeCase ? List.of(reference) : List.of(),
                                    new DeploymentResourceBindings(List.of(), Optional.of(List.of())),
                                    new DeploymentRuntimeDefinition.GoService("1.24", id, "main.go",
                                            new DeploymentHealthCheck.Tcp(
                                                    id.equals("db") ? 8080 : id.equals("api") ? 8081 : 8082, 10, 2)),
                                    Optional.empty(), DeploymentBuildLimits.defaultNonRoot(),
                                    new DeploymentReviewConsent(id, server.id(), material.snapshot().sha256(),
                                            archive.sha256(), Instant.now()),
                                    false, true);
                            plans.put(id, new ReviewedDeploymentPlanner().plan(request));
                        }
                        var orderedIds = scenario == ScenarioType.DUPLICATE_ORDER ? List.of("api", "api") : ids;
                        var waves = orderedIds.stream().map(List::of).toList();
                        var inverse = new ArrayList<>(orderedIds);
                        Collections.reverse(inverse);
                        var namespaces = new LinkedHashMap<String, String>();
                        var dependencies = new LinkedHashMap<String, List<String>>();
                        for (int position = 0; position < orderedIds.size(); position++) {
                            var id = orderedIds.get(position);
                            namespaces.put(id, id);
                            dependencies.put(id,
                                    scenario == ScenarioType.DUPLICATE_ORDER || position == 0
                                            ? List.of()
                                            : List.of(orderedIds.get(position - 1)));
                        }
                        var ordering = new MultiComponentDeploymentPlan("whole-app", waves, inverse, orderedIds,
                                orderedIds, inverse, namespaces, dependencies);
                        return new OperationResult<>(Outcome.SUCCEEDED,
                                Optional.of(new DeploymentStandardPlanner.ComponentPlans(ordering,
                                        scenario == ScenarioType.DUPLICATE_ORDER
                                                ? Map.of("api", plans.get("api"))
                                                : plans)),
                                Optional.empty());
                    });
            if (purposeCase) {
                var result = task.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                assertEquals(Outcome.FAILED, result.outcome(), result.toString());
                assertEquals(scenario == ScenarioType.PURPOSE_UNKNOWN
                        ? "DEPLOYMENT_SECRET_PURPOSE_UNAVAILABLE"
                        : "DEPLOYMENT_GRAPH_PREPARATION_INVALID", result.problem().orElseThrow().eventCode());
                assertTrue(actions.isEmpty(), actions.toString());
                assertEquals(0, countMod(runtime, "deployment_release"));
                assertEquals(0,
                        scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                return;
            }
            if (invalid || scenario == ScenarioType.CANCELLED_BEFORE_SOURCE) {
                var result = task.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                assertEquals(invalid ? Outcome.FAILED : Outcome.CANCELLED, result.outcome(),
                        () -> result + " actions=" + actions);
                assertTrue(actions.isEmpty(), actions.toString());
                assertEquals(0, countMod(runtime, "deployment_release"));
                assertEquals(0,
                        scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                return;
            }
            assertTrue(callbackEnded.await(30, TimeUnit.SECONDS), () -> "Remote session: " + task.snapshot());
            if (scenario == ScenarioType.TOTAL_SPACE_EXCEEDED) {
                var result = task.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals(Outcome.FAILED, result.outcome());
                assertEquals(2, actions.stream().filter("capacity"::equals).count());
                assertFalse(actions.stream().anyMatch(
                        value -> value.equals("upload") || value.equals("create-candidate") || value.equals("build")),
                        actions.toString());
                assertEquals(0, countMod(runtime, "deployment_release"));
                assertEquals(0,
                        scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                return;
            }
            if (scenario == ScenarioType.PUBLISH_UNKNOWN || scenario == ScenarioType.ROLLBACK_KNOWN_FAILURE
                    || scenario == ScenarioType.ORIGINAL_CLEANUP_REFUSED || scenario == ScenarioType.STAGE_UNKNOWN
                    || scenario == ScenarioType.CLOSE_UNKNOWN || scenario == ScenarioType.ROLLBACK_CLEANUP_REFUSED) {
                for (int attempt = 0; attempt < 200 && task.snapshot().state() != TaskState.UNKNOWN; attempt++)
                    Thread.sleep(10);
                assertEquals(TaskState.UNKNOWN, task.snapshot().state(), task.snapshot().toString());
                assertEquals(scenario == ScenarioType.ORIGINAL_CLEANUP_REFUSED || scenario == ScenarioType.CLOSE_UNKNOWN
                        ? 1
                        : 0, countMod(runtime, "deployment_release"));
                assertEquals(scenario == ScenarioType.STAGE_UNKNOWN ? 0 : 2,
                        actions.stream().filter("publish"::equals).count());
                assertEquals(
                        scenario == ScenarioType.ROLLBACK_KNOWN_FAILURE
                                || scenario == ScenarioType.ROLLBACK_CLEANUP_REFUSED
                                        ? List.of("rollback-web", "rollback-api")
                                        : List.of(),
                        actions.stream().filter(value -> value.startsWith("rollback-")).toList());
                assertEquals(scenario == ScenarioType.CLOSE_UNKNOWN ? 2 : 0,
                        actions.stream().filter("candidate-cleanup"::equals).count());
                assertTrue(
                        scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL") > 0);
                return;
            }
            OperationResult<Void> result;
            try {
                result = task.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            } catch (TimeoutException unfinished) {
                throw new AssertionError(
                        "Graph unfinished: " + task.snapshot() + ", actions=" + actions + ", releases="
                                + countMod(runtime, "deployment_release") + ", resources="
                                + scalar(runtime,
                                        "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"),
                        unfinished);
            }
            assertEquals(scenario == ScenarioType.SUCCESS ? Outcome.SUCCEEDED : Outcome.FAILED, result.outcome(),
                    () -> result + " actions=" + actions);
            assertEquals(scenario == ScenarioType.SUCCESS ? 1 : 0, countMod(runtime, "deployment_release"));
            assertEquals(scenario == ScenarioType.SUCCESS ? ids.size() : 0,
                    countMod(runtime, "deployment_unit_release_binding"));
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            assertEquals(ids.size(), actions.stream().filter("create-candidate"::equals).count());
            assertEquals(scenario == ScenarioType.MIDDLE_HEALTH_FAILED_ROLLBACK ? 2 : ids.size(),
                    actions.stream().filter("publish"::equals).count());
            if (scenario == ScenarioType.SUCCESS) {
                assertEquals(5, actions.stream().filter(value -> value.startsWith("health")).count());
                assertEquals("health-whole",
                        actions.stream().filter(value -> value.startsWith("health")).toList().getLast());
                assertFalse(actions.contains("rollback-web"), actions.toString());
            } else {
                assertEquals(
                        scenario == ScenarioType.MIDDLE_HEALTH_FAILED_ROLLBACK
                                ? List.of("rollback-web", "rollback-api", "rollback-db")
                                : List.of("rollback-web", "rollback-api"),
                        actions.stream().filter(value -> value.startsWith("rollback-")).toList());
                assertFalse(actions.contains("health-whole"), actions.toString());
                assertEquals(ids.size(), actions.stream().filter("candidate-cleanup"::equals).count());
                if (scenario == ScenarioType.MIDDLE_HEALTH_FAILED_ROLLBACK) {
                    assertEquals(List.of("stop-web", "stop-api", "stop-db"),
                            actions.stream().filter(value -> value.startsWith("stop-")).toList());
                    assertTrue(actions.lastIndexOf("snapshot") < actions.indexOf("stop-web"), actions.toString());
                    assertEquals(List.of("db", "api"), actions.stream().filter(value -> value.startsWith("published-"))
                            .map(value -> value.substring("published-".length())).toList());
                }
            }
            assertTrue(actions.lastIndexOf("build") < actions.indexOf("stage-config"), actions.toString());
            assertTrue(actions.lastIndexOf("stage-config") < actions.indexOf("snapshot"), actions.toString());
            assertTrue(actions.lastIndexOf("snapshot") < actions.indexOf("publish"), actions.toString());
            assertEquals(scenario == ScenarioType.MIDDLE_HEALTH_FAILED_ROLLBACK ? List.of("db", "api") : ids,
                    actions.stream().filter(value -> value.startsWith("published-"))
                            .map(value -> value.substring("published-".length())).toList());
        } finally {
            if (!interruptedHost)
                runtime.close();
        }
    }

    /** Saves an original credential and optionally models a migrated unknown-purpose revision. / 保存原凭据并可模拟迁移后用途未知的修订。
     * @param context original Mod / 原Mod
     * @param reference exact immutable revision / 精确不可变修订
     * @param unknownPurpose whether migrated metadata lacks purpose / 迁移元数据是否缺用途
     * @return original opaque credential / 原不透明凭据
     * @throws Exception if actual storage fails / 实际存储失败时 */
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

    /** Reads actual Mod schema rows. / 读取真实Mod schema行。 */
    private long countMod(PlatformRuntime runtime, String table) throws Exception {
        try (var access = scope(runtime, true);
                var connection = access.openConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    /** Binds the controlled SSH session while preserving actual source, task and interaction capabilities. / 绑定受控SSH会话并保留真实来源、任务及交互能力。 */
    private ModContext withSession(ModContext real, RemoteSession session) {
        LinuxService linux = (LinuxService) Proxy.newProxyInstance(LinuxService.class.getClassLoader(),
                new Class<?>[]{LinuxService.class}, (proxy, method, args) -> {
                    assertEquals("connect", method.getName());
                    return session;
                });
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("linux"))
                        return linux;
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

    /** Returns bounded original helper receipts for both reviewed components. / 为两个审阅组件返回有界原helper回执。 */
    private RemoteSession peer(ScenarioType scenario, List<String> actions, AtomicReference<String> sha,
            AtomicLong bytes, AtomicReference<Path> path, CountDownLatch callbackEnded) {
        var running = new ConcurrentHashMap<String, Boolean>();
        var firstStage = new AtomicBoolean();
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                    if (method.getName().equals("facts"))
                        return new RemoteFacts("", "", "SHA256:fixture-host");
                    if (method.getName().equals("close")) {
                        callbackEnded.countDown();
                        if (scenario == ScenarioType.CLOSE_UNKNOWN)
                            throw new java.io.IOException("Controlled session close acknowledgement lost");
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
                        return reply("HELPER=1\nPROTOCOL=12\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256
                                + "\n");
                    }
                    if (script.contains("DISTRO_ID=")) {
                        actions.add("host-facts");
                        return reply(
                                "DISTRO_ID=ubuntu\nVERSION=24.04\nARCH=x86_64\nPACKAGE_MANAGER=apt\nPACKAGE_ARCH=amd64\nSYSTEMD=1\nCPU_LEVEL=x86-64-v3\nSECURITY_MODULE=apparmor\nSECURITY_STATE=enabled\nFIREWALL=ufw\nFIREWALL_STATE=active\n");
                    }
                    if (script.contains("stat -f -c")) {
                        actions.add("capacity");
                        return reply(
                                scenario == ScenarioType.TOTAL_SPACE_EXCEEDED ? "2000000 4096\n" : "1000000000 4096\n");
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
                        String candidate = script.contains("'db'") ? "db" : script.contains("'api'") ? "api" : "web";
                        return reply("CANDIDATE=" + new DeploymentRemoteWorkspace(candidate, sha.get()).candidateRoot()
                                + "\n");
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
                        return scenario == ScenarioType.STAGE_UNKNOWN && firstStage.compareAndSet(false, true)
                                ? new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                                        Optional.of(new Problem(ProblemCode.UNKNOWN, "FIXTURE_GRAPH_STAGE_ACK_LOST",
                                                "Controlled acknowledgement was lost", Map.of(), Optional.empty())))
                                : reply("INPUT_STAGED=1\n");
                    }
                    if (script.contains("stage-secret")) {
                        actions.add("stage-secret");
                        assertTrue(script.contains("'db-"), "Native database business reference must be staged");
                        assertTrue(script.contains("'1'"), "The exact immutable revision must be staged");
                        byte[] password = ((CommandRequest) args[0]).stdin();
                        try {
                            assertTrue(password.length > 0);
                        } finally {
                            Arrays.fill(password, (byte) 0);
                        }
                        return reply("INPUT_STAGED=1\n");
                    }
                    if (script.contains("snapshot-deployment")) {
                        actions.add("snapshot");
                        if (scenario == ScenarioType.MIDDLE_HEALTH_FAILED_ROLLBACK) {
                            var taskToken = java.util.regex.Pattern
                                    .compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}").matcher(script);
                            assertTrue(taskToken.find(), script);
                            return reply("SNAPSHOT_TOKEN=" + taskToken.group()
                                    + "\nPREVIOUS=1\nPREVIOUS_RUNNING=1\nPREVIOUS_DIGEST=" + "d".repeat(64)
                                    + "\nPREVIOUS_KIND=deployment\n");
                        }
                        return reply("PREVIOUS=0\n");
                    }
                    if (script.contains("lifecycle-deployment")) {
                        var id = script.contains("'web'") ? "web" : script.contains("'api'") ? "api" : "db";
                        actions.add("stop-" + id);
                        running.put(id, false);
                        return reply("LIFECYCLE=stop\n");
                    }
                    if (script.contains("publish-deployment")) {
                        actions.add("publish");
                        var id = script.contains("'db'") ? "db" : script.contains("'api'") ? "api" : "web";
                        actions.add("published-" + id);
                        running.put(id, true);
                        if (recoveryPort != 22) {
                            var release = java.util.regex.Pattern
                                    .compile("'publish-deployment' '[a-z0-9-]+' '[a-z0-9-]+' '([0-9a-f]{64})'")
                                    .matcher(script);
                            assertTrue(release.find(), script);
                            Files.writeString(root.resolve("controlled-graph-releases"),
                                    id + "=" + release.group(1) + "\n", java.nio.file.StandardOpenOption.CREATE,
                                    java.nio.file.StandardOpenOption.APPEND);
                        }
                        return scenario == ScenarioType.PUBLISH_UNKNOWN && id.equals("web")
                                ? new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                                        Optional.of(new Problem(ProblemCode.UNKNOWN, "FIXTURE_GRAPH_PUBLISH_ACK_LOST",
                                                "Controlled acknowledgement was lost", Map.of(), Optional.empty())))
                                : reply("PUBLISHED=1\n");
                    }
                    if (script.contains("application-health")) {
                        if (((scenario == ScenarioType.HEALTH_FAILED_ROLLBACK
                                || scenario == ScenarioType.ROLLBACK_CLEANUP_REFUSED
                                || scenario == ScenarioType.ROLLBACK_KNOWN_FAILURE)
                                && script.contains(DeploymentWorkloadArguments
                                        .healthPayload(new DeploymentHealthCheck.Tcp(8082, 10, 2))))
                                || scenario == ScenarioType.MIDDLE_HEALTH_FAILED_ROLLBACK
                                        && script.contains(DeploymentWorkloadArguments
                                                .healthPayload(new DeploymentHealthCheck.Tcp(8081, 10, 2)))) {
                            actions.add("health-web-failed");
                            return reply(1, "HEALTHY=0\n");
                        }
                        actions.add(script.contains(
                                DeploymentWorkloadArguments.healthPayload(new DeploymentHealthCheck.Tcp(8099, 10, 2)))
                                        ? "health-whole"
                                        : "health-component");
                        return reply("HEALTHY=1\n");
                    }
                    if (script.contains("rollback-deployment")) {
                        var id = script.contains("'db'") ? "db" : script.contains("'api'") ? "api" : "web";
                        actions.add("rollback-" + id);
                        running.put(id, true);
                        return scenario == ScenarioType.ROLLBACK_KNOWN_FAILURE && id.equals("web")
                                ? reply(1, "ROLLBACK_FAILED=1\n")
                                : reply("ROLLED_BACK=1\n");
                    }
                    if (script.contains("inspect-runtime")) {
                        actions.add("inspect-runtime");
                        return reply("KIND=deployment\nMODE=DAEMON\n");
                    }
                    if (script.contains("observe-deployment")) {
                        actions.add("observe");
                        var id = script.contains("'db'") ? "db" : script.contains("'api'") ? "api" : "web";
                        boolean active = running.getOrDefault(id, true);
                        return reply("OWNER=1\nRUNNING=" + (active ? "1" : "0")
                                + "\nENABLED=enabled\nQUERY_OK=1\nActiveState=" + (active ? "active" : "inactive")
                                + "\nSubState=" + (active ? "running" : "dead")
                                + "\nResult=success\nExecMainCode=0\nExecMainStatus=0\nMainPID="
                                + (active ? "123" : "0") + "\n");
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
                        return reply("RETAINED=1\n");
                    }
                    throw new AssertionError(script);
                });
    }

    /** Complete fixed command response. / 完整固定命令回执。 */
    private static OperationResult<CommandResult> reply(String output) {
        return reply(0, output);
    }

    /** Returns a complete observed native exit. / 返回完整已观察原生退出。 */
    private static OperationResult<CommandResult> reply(int exit, String output) {
        return new OperationResult<>(Outcome.SUCCEEDED,
                Optional.of(new CommandResult(Optional.of(exit), output, "", false)), Optional.empty());
    }
}
