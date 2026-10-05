package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.file.git.*;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentPreparedDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises frozen-source discovery, edited input and archive confirmation within one real CLASS/H2 task. / 在真实CLASS/H2同一任务中验证冻结来源发现、编辑输入及归档确认。 */
class DeploymentStaticWorkflowTest extends DeploymentRuntimeFixture {
    private boolean gitScenario;

    @Override
    protected boolean gitEnabled() {
        return gitScenario;
    }

    private enum RefusalStageType {
        GRAPH_SELECTION, RUNTIME_REVIEW, UNKNOWN_DATABASE_INPUTS
    }

    private static final Path GIT = Arrays.stream(System.getenv("PATH").split(java.io.File.pathSeparator))
        .map(path -> Path.of(path).resolve("git.exe")).filter(Files::isRegularFile).findFirst().orElseThrow()
        .toAbsolutePath().normalize();

    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    /** Confirms one or two real source subtrees before a user refusal, without any remote action. / 在用户拒绝前确认一份或两份真实来源子树，不执行远端动作。
     * @param count expected component count / 预期组件数
     * @throws Exception if original local task setup fails / 原本地任务设置失败时 */
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void originalTaskDiscoversReviewsAndArchivesBeforeFinalRefusal(int count) throws Exception {
        runReview(count, false, false);
    }

    /** Real local Git pins the first commit and uses the same one- or two-component review after its branch moves. / 真实本地 Git 在分支移动后仍固定首个提交，并使用相同单项或双项审阅。
     * @param count actual component count / 实际组件数
     * @throws Exception if native Git or original review fails / 原生 Git 或原审阅失败时 */
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void gitSourcePinsCommitBeforeReviewAndKeepsOneOriginalTask(int count) throws Exception {
        runReview(count, false, true);
    }

    /** Source schema mutations require a separate SQLite initialization decision before runtime review. / 源码模式变更在运行审阅前要求单独 SQLite 初始化决定。
     * @throws Exception if real source or task setup fails / 真实来源或任务设置失败时 */
    @Test
    void schemaMutationNeedsSeparateSqliteConsentBeforeRuntimeForm() throws Exception {
        runReview(1, true, false);
    }

    /** Cancellation after SQLite consent remains a known local cancellation before runtime review. / SQLite 同意后取消仍是运行审阅前的已知本机取消。
     * @throws Exception if the original task or frozen source fails / 原任务或冻结源码失败时 */
    @Test
    void cancellationAfterSqliteConsentReleasesOriginalSourceWithoutPublication() throws Exception {
        runReview(1, true, false, true);
    }

    /** Refusal in each local review stage releases the actual source and checkpoint before any capability effect. / 各本机审阅阶段拒绝后，在任一能力效果前释放真实来源和检查点。
     * @param stage exact original review boundary / 精确原审阅边界
     * @throws Exception if original task or local source fails / 原任务或本机来源失败时 */
    @ParameterizedTest(name = "{0}")
    @EnumSource(RefusalStageType.class)
    void localGraphRuntimeAndUnknownDatabaseRefusalCancelTheOriginalTaskWithoutRemoteWork(RefusalStageType stage)
        throws Exception {
        int count = stage == RefusalStageType.GRAPH_SELECTION ? 2 : 1;
        for (int i = 0; i < count; i++) {
            Path directory = count == 1
                ? selectedSourceDirectory()
                : selectedSourceDirectory().resolve(i == 0 ? "api" : "web");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("go.mod"), "module example.test/refusal" + i + "\n\ngo 1.24\n");
            Files.writeString(directory.resolve("go.sum"), "example.test/dependency v1 h1:fixture\n");
            Files.writeString(directory.resolve("main.go"), "package main\nfunc main() {}\n");
            if (stage == RefusalStageType.UNKNOWN_DATABASE_INPUTS)
                Files.writeString(directory.resolve("schema.sql"), "CREATE TABLE sample(id integer);\n");
        }
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile
                    .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            var sourceCalls = new AtomicInteger();
            var snapshots = new AtomicInteger();
            var ssh = new AtomicInteger();
            var models = new AtomicInteger();
            var source = gold.debug.windowstolinux.mod.deployment.file.DeploymentFileServiceFixture.create(FileService.class.getClassLoader(), (proxy, method, arguments) -> {
                if (method.getName().equals("selectDirectory"))
                    sourceCalls.incrementAndGet();
                if (method.getName().equals("snapshot"))
                    snapshots.incrementAndGet();
                try {
                    return gold.debug.windowstolinux.mod.deployment.file.DeploymentFileServiceFixture.invoke(method, actual.file(), arguments);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
            var linux = (LinuxService) Proxy.newProxyInstance(LinuxService.class.getClassLoader(),
                new Class<?>[]{LinuxService.class}, (proxy, method, arguments) -> {
                    ssh.incrementAndGet();
                    throw new AssertionError("Local refusal cannot connect, observe or stage: " + method.getName());
                });
            var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(),
                new Class<?>[]{AiService.class}, (proxy, method, arguments) -> {
                    models.incrementAndGet();
                    throw new AssertionError(
                        "Ordinary local review cannot consult model inventory or send: " + method.getName());
                });
            var bound = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("file"))
                        return source;
                    if (method.getName().equals("linux"))
                        return linux;
                    if (method.getName().equals("ai"))
                        return ai;
                    try {
                        return method.invoke(actual, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var selected = new AtomicInteger();
            var reviewed = new AtomicInteger();
            var database = new AtomicInteger();
            var original = new java.util.concurrent.atomic.AtomicReference<UUID>();
            var reviewer = new DeploymentStaticWorkflow.Reviewer() {
                public OperationResult<DeploymentStaticWorkflow.Selection> select(ServerId server, String sha,
                                                                                  List<DiscoveredProjectPart> discovered, TaskContext task) throws Exception {
                    selected.incrementAndGet();
                    original.set(task.id());
                    assertEquals(count, discovered.size());
                    assertTrue(scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + task.id()
                        + "' AND resource_kind='FILE' AND released_at IS NULL") > 0);
                    assertTrue(scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + task.id()
                        + "' AND resource_kind='CUSTOM' AND resource_id LIKE '%/checkpoint/%' AND released_at IS NULL") > 0);
                    if (stage == RefusalStageType.GRAPH_SELECTION)
                        return cancelled();
                    String id = discovered.getFirst().id();
                    return success(new DeploymentStaticWorkflow.Selection(id,
                        Map.of(id, new DeploymentStaticWorkflow.Choice(DeploymentProjectKind.GO_SERVICE, Set.of())),
                        Optional.empty()));
                }

                public OperationResult<Map<String, String>> databaseInputs(ServerId server, String componentId,
                                                                           String sha, Path directory, String applicationId,
                                                                           gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.db.DatabaseProjectChecker.Report assessment,
                                                                           TaskContext task) {
                    assertEquals(RefusalStageType.UNKNOWN_DATABASE_INPUTS, stage);
                    assertEquals(original.get(), task.id());
                    database.incrementAndGet();
                    assertTrue(assessment.unknownDatabase());
                    assertTrue(assessment.databases().isEmpty());
                    assertEquals(List.of("schema.sql"), assessment.sqlCandidates());
                    return cancelled();
                }

                public OperationResult<Map<String, DeploymentStaticWorkflow.Answers>> review(ServerId server,
                                                                                             String sha, List<DeploymentStaticWorkflow.Prompt> prompts, TaskContext task) {
                    assertEquals(RefusalStageType.RUNTIME_REVIEW, stage);
                    assertEquals(original.get(), task.id());
                    assertEquals(1, prompts.size());
                    reviewed.incrementAndGet();
                    return cancelled();
                }

                public OperationResult<Boolean> confirm(ServerId server, String sha, Map<String, String> archives,
                                                        Map<String, DeploymentStaticWorkflow.Answers> answers,
                                                        Map<String, DeploymentPreparedDatabase> databases,
                                                        Optional<DeploymentStaticWorkflow.GitReview> git, TaskContext task) {
                    throw new AssertionError("Refused local fields cannot reach publication consent");
                }
            };
            var handle = new DeploymentStaticWorkflow(bound, new DeploymentRefFlow(bound)).submit(
                new ServerId("server"), new DeploymentStaticWorkflow.Draft("", 65536, 100, 65536),
                Optional.empty(), reviewer);
            var result = handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(TaskResult.CANCELLED, result.outcome(), result.toString());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(Optional.of(TaskResult.CANCELLED), handle.snapshot().outcome());
            assertEquals(original.get(), handle.id());
            assertEquals(1, selected.get());
            assertEquals(stage == RefusalStageType.RUNTIME_REVIEW ? 1 : 0, reviewed.get());
            assertEquals(stage == RefusalStageType.UNKNOWN_DATABASE_INPUTS ? 1 : 0, database.get());
            assertEquals(1, sourceCalls.get());
            assertEquals(1, snapshots.get());
            assertEquals(0, ssh.get());
            assertEquals(0, models.get());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + handle.id()
                + "' AND released_at IS NULL"));
            try (var access = scope(runtime, true);
                 var connection = access.openConnection();
                 var statement = connection.createStatement()) {
                for (String table : List.of("deployment_execution", "managed_application", "deployment_unit",
                    "deployment_release", "deployment_project"))
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getLong(1), table);
                    }
            }
        }
    }

    /** Real page admission retains both original checkpoints when Git preparation cleanup is initially unknown. / 真实页面准入在 Git 准备收尾暂未知时保留两个原检查点。
     * @throws Exception if the original task or registered recovery fails / 原任务或已登记恢复失败时 */
    @Test
    void originalWorkflowKeepsRecoveryMarkerWhenGitPreparationIsUnknown() throws Exception {
        gitScenario = true;
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("go.mod"), "module example.test/recovery\n\ngo 1.24\n");
        command(selectedSourceDirectory(), "init", "-b", "main");
        command(selectedSourceDirectory(), "config", "user.email", "test@example.invalid");
        command(selectedSourceDirectory(), "config", "user.name", "Test");
        command(selectedSourceDirectory(), "add", ".");
        command(selectedSourceDirectory(), "commit", "-m", "first");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile
                    .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var original = runtime.modContexts().get(OWNER).context();
            var cleanupCalls = new AtomicInteger();
            var captureCalls = new AtomicInteger();
            var source = gold.debug.windowstolinux.mod.deployment.file.DeploymentFileServiceFixture.create(FileService.class.getClassLoader(), (proxy, method, arguments) -> {
                if (method.getName().equals("snapshot")) {
                    captureCalls.incrementAndGet();
                    throw new ApiException(new Problem(ProblemCode.UNKNOWN, "TEST_GIT_SOURCE_CAPTURE_UNCONFIRMED",
                        "Original deployment source capture is unavailable", Map.of(), Optional.empty()));
                }
                if (method.getName().equals("closeTaskResources") && cleanupCalls.getAndIncrement() == 0)
                    throw new ApiException(new Problem(ProblemCode.UNKNOWN, "TEST_GIT_CLEANUP_UNCONFIRMED",
                        "Original source cleanup is unavailable", Map.of(), Optional.empty()));
                try {
                    return gold.debug.windowstolinux.mod.deployment.file.DeploymentFileServiceFixture.invoke(method, original.file(), arguments);
                } catch (InvocationTargetException wrapped) {
                    throw wrapped.getCause();
                }
            });
            var bound = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("file"))
                        return source;
                    try {
                        return method.invoke(original, arguments);
                    } catch (InvocationTargetException wrapped) {
                        throw wrapped.getCause();
                    }
                });
            var workflow = GitRuntimeTestBridge.staticWorkflow(runtime, bound);
            var request = new DeploymentGitRequest(new DeploymentGitRemote(selectedSourceDirectory().toUri()),
                new DeploymentGitRef.DefaultBranch(), Set.of(), 65536, true);
            var reviewer = new DeploymentStaticWorkflow.Reviewer() {
                public OperationResult<DeploymentStaticWorkflow.Selection> select(ServerId server, String manifest,
                                                                                  List<DiscoveredProjectPart> discovered, TaskContext task) {
                    throw new AssertionError("Git preparation did not finish");
                }

                public OperationResult<Map<String, DeploymentStaticWorkflow.Answers>> review(ServerId server,
                                                                                             String manifest, List<DeploymentStaticWorkflow.Prompt> prompts, TaskContext task) {
                    throw new AssertionError("Git preparation did not finish");
                }

                public OperationResult<Boolean> confirm(ServerId server, String manifest, Map<String, String> archives,
                                                        Map<String, DeploymentStaticWorkflow.Answers> answers,
                                                        Map<String, DeploymentPreparedDatabase> databases,
                                                        Optional<DeploymentStaticWorkflow.GitReview> git, TaskContext task) {
                    throw new AssertionError("Git preparation did not finish");
                }
            };
            var handle = workflow.submit(new ServerId("server"),
                new DeploymentStaticWorkflow.Draft("reviewed-project", 65536, 100, 65536),
                Optional.of(new DeploymentStaticWorkflow.GitSource(request, GIT)), reviewer);
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < deadline)
                Thread.sleep(50);
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            assertEquals(1, captureCalls.get(), "Actual Git material reaches the original deployment capture");
            var retained = new HashSet<String>();
            try (var connection = runtime.persistence().database().openConnection();
                 var query = connection.prepareStatement("SELECT resource_id FROM platform_task_resource"
                     + " WHERE task_id=? AND resource_kind='CUSTOM' AND released_at IS NULL")) {
                query.setObject(1, handle.id());
                try (var rows = query.executeQuery()) {
                    while (rows.next()) assertTrue(retained.add(rows.getString(1)));
                }
            }
            assertEquals(Set.of(OWNER + "/checkpoint/deployment-static-preparation",
                OWNER + "/checkpoint/deployment-git-source",
                OWNER + "/checkpoint/deployment-publication-policy"), retained);
            var recovered = reconcileReady(runtime, handle);
            assertEquals(TaskState.ENDED, recovered.state());
            assertEquals(Optional.of(TaskResult.FAILED), recovered.outcome());
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            assertEquals(1, cleanupCalls.get(), "Recovery uses the registered unwrapped source capability");
        }
    }

    private void runReview(int count, boolean schema, boolean git) throws Exception {
        runReview(count, schema, git, false);
    }

    private void runReview(int count, boolean schema, boolean git, boolean cancelAfterConsent) throws Exception {
        gitScenario = git;
        for (int i = 0; i < count; i++) {
            Path project = count == 1
                ? selectedSourceDirectory()
                : selectedSourceDirectory().resolve(i == 0 ? "api" : "web");
            Files.createDirectories(project);
            Files.writeString(project.resolve("go.mod"),
                "module example.test/" + (i == 0 ? "api" : "web") + "\n\ngo 1.24\n");
            Files.writeString(project.resolve("go.sum"), "example.test/dependency v1 h1:fixture\n");
            Files.writeString(project.resolve("main.go"), "package main\nfunc main() {}\n");
            if (schema)
                Files.writeString(project.resolve("schema.sql"), "CREATE TABLE sample(id integer);\n");
        }
        String firstCommit = null;
        if (git) {
            command(selectedSourceDirectory(), "init", "-b", "main");
            command(selectedSourceDirectory(), "config", "user.email", "test@example.invalid");
            command(selectedSourceDirectory(), "config", "user.name", "Test");
            command(selectedSourceDirectory(), "add", ".");
            command(selectedSourceDirectory(), "commit", "-m", "first");
            firstCommit = command(selectedSourceDirectory(), "rev-parse", "HEAD");
        }
        final String expectedCommit = firstCommit;
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile
                    .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var reviewed = new AtomicInteger();
            var source = new java.util.concurrent.atomic.AtomicReference<String>();
            var archives = new java.util.concurrent.atomic.AtomicReference<Map<String, String>>();
            var taskId = new java.util.concurrent.atomic.AtomicReference<UUID>();
            var confirmationEntered = new CountDownLatch(1);
            var confirmationRelease = new CountDownLatch(1);
            var workflow = GitRuntimeTestBridge.staticWorkflow(runtime, context);
            var reviewer = new DeploymentStaticWorkflow.Reviewer() {
                @Override
                public OperationResult<Map<String, String>> databaseInputs(ServerId server, String componentId,
                                                                           String sha, Path componentRoot, String applicationId,
                                                                           gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.db.DatabaseProjectChecker.Report assessment,
                                                                           TaskContext task) {
                    assertTrue(schema);
                    assertEquals(taskId.get(), task.id());
                    return success(Map.of("database.main.engine", "SQLITE", "database.main.path", "DEFAULT",
                        "database.main.pathEnvironment", "DATABASE_PATH", "database.main.initialize",
                        "schema.sql"));
                }

                @Override
                public OperationResult<Boolean> confirmSqliteInitialization(ServerId server, String componentId,
                                                                            String sha,
                                                                            gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.db.DatabaseProjectChecker.Report assessment,
                                                                            TaskContext task) throws Exception {
                    assertTrue(schema);
                    assertEquals(List.of("schema.sql"), assessment.databases().getFirst().initializationFiles());
                    if (cancelAfterConsent) {
                        confirmationEntered.countDown();
                        assertTrue(confirmationRelease.await(20, TimeUnit.SECONDS));
                        return success(Boolean.TRUE);
                    }
                    return cancelled();
                }

                @Override
                public OperationResult<DeploymentStaticWorkflow.Selection> select(ServerId server, String sha,
                                                                                  List<DiscoveredProjectPart> discovered, TaskContext task) throws Exception {
                    assertEquals(count, discovered.size());
                    taskId.set(task.id());
                    source.set(sha);
                    if (git) {
                        Files.writeString(selectedSourceDirectory().resolve("later.txt"), "later branch content");
                        command(selectedSourceDirectory(), "add", ".");
                        command(selectedSourceDirectory(), "commit", "-m", "later");
                    }
                    var choices = new HashMap<String, DeploymentStaticWorkflow.Choice>();
                    for (var component : discovered)
                        choices.put(component.id(),
                            new DeploymentStaticWorkflow.Choice(DeploymentProjectKind.GO_SERVICE, Set.of()));
                    return success(new DeploymentStaticWorkflow.Selection(
                        count == 1 ? discovered.getFirst().id() : "reviewed-application", choices,
                        count == 1 ? Optional.empty() : Optional.of(discovered.getFirst().id())));
                }

                @Override
                public OperationResult<Map<String, DeploymentStaticWorkflow.Answers>> review(ServerId server,
                                                                                             String sha, List<DeploymentStaticWorkflow.Prompt> prompts, TaskContext task) {
                    assertEquals(taskId.get(), task.id());
                    assertEquals(source.get(), sha);
                    var result = new HashMap<String, DeploymentStaticWorkflow.Answers>();
                    for (int i = 0; i < prompts.size(); i++) {
                        var prompt = prompts.get(i);
                        var values = new HashMap<>(prompt.defaults());
                        values.put("primary", prompt.componentId());
                        values.put("secondary", "main.go");
                        values.put("version", "1.24");
                        values.put("port", Integer.toString(8080 + i));
                        values.put("healthMode", "TCP");
                        values.put("exposure", "INTERNAL");
                        result.put(prompt.componentId(), new DeploymentStaticWorkflow.Answers(values, List.of(),
                            DeploymentBuildLimits.defaultNonRoot(), false, true));
                        workflow.validateReview(prompt, result.get(prompt.componentId()));
                    }
                    reviewed.incrementAndGet();
                    return success(Map.copyOf(result));
                }

                @Override
                public OperationResult<Boolean> confirm(ServerId server, String sha, Map<String, String> archiveSha,
                                                        Map<String, DeploymentStaticWorkflow.Answers> answers,
                                                        Map<String, DeploymentPreparedDatabase> databases,
                                                        Optional<DeploymentStaticWorkflow.GitReview> gitReview, TaskContext task) {
                    assertFalse(schema, "Schema review must block before final publication consent");
                    assertEquals(taskId.get(), task.id());
                    assertEquals(source.get(), sha);
                    assertEquals(count, archiveSha.size());
                    assertEquals(archiveSha.keySet(), answers.keySet());
                    gitReviewPresent(gitReview, git, expectedCommit);
                    archiveSha.values().forEach(value -> assertTrue(value.matches("[a-f0-9]{64}")));
                    archives.set(Map.copyOf(archiveSha));
                    return cancelled();
                }
            };
            var gitInput = git
                ? Optional.of(new DeploymentStaticWorkflow.GitSource(
                new DeploymentGitRequest(new DeploymentGitRemote(selectedSourceDirectory().toUri()),
                    new DeploymentGitRef.DefaultBranch(), Set.of(), 65536, true),
                GIT))
                : Optional.<DeploymentStaticWorkflow.GitSource>empty();
            var handle = workflow.submit(new ServerId("server"),
                new DeploymentStaticWorkflow.Draft(git ? "reviewed-git-project" : "", 65536, 100, 65536), gitInput,
                reviewer);
            if (cancelAfterConsent)
                try {
                    assertTrue(confirmationEntered.await(20, TimeUnit.SECONDS));
                    handle.requestCancel();
                } finally {
                    confirmationRelease.countDown();
                }
            var result = handle.completion().toCompletableFuture().get(90, TimeUnit.SECONDS);
            assertEquals(TaskResult.CANCELLED, result.outcome(), result.toString());
            assertEquals(schema ? 0 : 1, reviewed.get());
            if (!schema)
                assertEquals(count, archives.get().size());
            if (git) {
                try (var access = scope(runtime, true);
                     var connection = access.openConnection();
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_project")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getLong(1));
                }
                assertNotEquals(expectedCommit, command(selectedSourceDirectory(), "rev-parse", "HEAD"));
            }
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
            if (cancelAfterConsent)
                try (var access = scope(runtime, true);
                     var connection = access.openConnection();
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_execution")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getLong(1));
                }
        }
    }

    private static boolean gitReviewPresent(Optional<DeploymentStaticWorkflow.GitReview> actual, boolean expected,
                                            String commit) {
        assertEquals(expected, actual.isPresent());
        actual.ifPresent(value -> {
            assertEquals("DEFAULT:HEAD", value.reference());
            assertEquals(commit, value.commit());
        });
        return actual.isPresent();
    }

    private static String command(Path directory, String... arguments) throws Exception {
        var words = new ArrayList<String>();
        words.add(GIT.toString());
        words.addAll(List.of(arguments));
        var process = new ProcessBuilder(words).directory(directory.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().close();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    private static <T> OperationResult<T> cancelled() {
        return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.of(new Problem(ProblemCode.CANCELLED,
            "DEPLOYMENT_STATIC_TEST_REFUSED", "Original review was refused", Map.of(), Optional.empty())));
    }
}
