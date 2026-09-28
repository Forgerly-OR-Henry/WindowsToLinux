package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.source.SourceService;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectComponent;
import gold.debug.windowstolinux.mod.deployment.source.git.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises frozen-source discovery, edited input and archive confirmation within one real CLASS/H2 task. / 在真实CLASS/H2同一任务中验证冻结来源发现、编辑输入及归档确认。 */
class DeploymentStaticWorkflowTest extends DeploymentRuntimeFixture {
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

    /** Source schema mutations reach the review form, then stop before archive or remote effect without verified DB work. / 源码模式变更可进入审阅表单，但缺少已验证数据库操作时在归档与远端效果前停止。
     * @throws Exception if real source or task setup fails / 真实来源或任务设置失败时 */
    @Test
    void schemaMutationNeedsSeparateVerifiedReviewAfterRuntimeForm() throws Exception {
        runReview(1, true, false);
    }

    /** Real page admission retains both original checkpoints when Git preparation cleanup is initially unknown. / 真实页面准入在 Git 准备收尾暂未知时保留两个原检查点。
     * @throws Exception if the original task or registered recovery fails / 原任务或已登记恢复失败时 */
    @Test
    void originalWorkflowKeepsRecoveryMarkerWhenGitPreparationIsUnknown() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                    ServerProfile
                            .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                    "fixture-password".toCharArray());
            var original = runtime.modContexts().get(OWNER).context();
            var cleanupCalls = new AtomicInteger();
            var source = (SourceService) Proxy.newProxyInstance(SourceService.class.getClassLoader(),
                    new Class<?>[]{SourceService.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("workspace") && arguments.length == 2)
                            throw new ApiException(new Problem(ProblemCode.UNKNOWN, "TEST_GIT_WORKSPACE_UNCONFIRMED",
                                    "Original workspace result is unavailable", Map.of(), Optional.empty()));
                        if (method.getName().equals("closeTaskResources") && cleanupCalls.getAndIncrement() == 0)
                            throw new ApiException(new Problem(ProblemCode.UNKNOWN, "TEST_GIT_CLEANUP_UNCONFIRMED",
                                    "Original source cleanup is unavailable", Map.of(), Optional.empty()));
                        try {
                            return method.invoke(original.source(), arguments);
                        } catch (InvocationTargetException wrapped) {
                            throw wrapped.getCause();
                        }
                    });
            var bound = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                    new Class<?>[]{ModContext.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("source"))
                            return source;
                        try {
                            return method.invoke(original, arguments);
                        } catch (InvocationTargetException wrapped) {
                            throw wrapped.getCause();
                        }
                    });
            var workflow = new DeploymentStaticWorkflow(bound, new DeploymentReferenceFlow(bound));
            var request = new DeploymentGitRequest(new DeploymentGitRemote(root.resolve("unused-repo").toUri()),
                    new DeploymentGitReference.DefaultBranch(), Set.of(), 65536, true);
            var reviewer = new DeploymentStaticWorkflow.Reviewer() {
                public OperationResult<DeploymentStaticWorkflow.Selection> select(ServerRef server, String manifest,
                        List<DiscoveredProjectComponent> discovered, TaskContext task) {
                    throw new AssertionError("Git preparation did not finish");
                }

                public OperationResult<Map<String, DeploymentStaticWorkflow.Answers>> review(ServerRef server,
                        String manifest, List<DeploymentStaticWorkflow.Prompt> prompts, TaskContext task) {
                    throw new AssertionError("Git preparation did not finish");
                }

                public OperationResult<Boolean> confirm(ServerRef server, String manifest, Map<String, String> archives,
                        Map<String, DeploymentStaticWorkflow.Answers> answers,
                        Optional<DeploymentStaticWorkflow.GitReview> git, TaskContext task) {
                    throw new AssertionError("Git preparation did not finish");
                }
            };
            var handle = workflow.submit(new ServerRef("server"),
                    new DeploymentStaticWorkflow.Draft("reviewed-project", 65536, 100, 65536),
                    Optional.of(new DeploymentStaticWorkflow.GitSource(request, GIT)), reviewer);
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < deadline)
                Thread.sleep(50);
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            assertEquals(2, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='CUSTOM' AND released_at IS NULL"));
            var recovered = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, recovered.state());
            assertEquals(Optional.of(Outcome.FAILED), recovered.outcome());
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind IN ('CUSTOM','FILE','PROCESS') AND released_at IS NULL"));
            assertEquals(1, cleanupCalls.get(), "Recovery uses the registered unwrapped source capability");
        }
    }

    private void runReview(int count, boolean schema, boolean git) throws Exception {
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
            var workflow = new DeploymentStaticWorkflow(context, new DeploymentReferenceFlow(context));
            var reviewer = new DeploymentStaticWorkflow.Reviewer() {
                @Override
                public OperationResult<DeploymentStaticWorkflow.Selection> select(ServerRef server, String sha,
                        List<DiscoveredProjectComponent> discovered, TaskContext task) throws Exception {
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
                public OperationResult<Map<String, DeploymentStaticWorkflow.Answers>> review(ServerRef server,
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
                public OperationResult<Boolean> confirm(ServerRef server, String sha, Map<String, String> archiveSha,
                        Map<String, DeploymentStaticWorkflow.Answers> answers,
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
                                    new DeploymentGitReference.DefaultBranch(), Set.of(), 65536, true),
                            GIT))
                    : Optional.<DeploymentStaticWorkflow.GitSource>empty();
            var handle = workflow.submit(new ServerRef("server"),
                    new DeploymentStaticWorkflow.Draft(git ? "reviewed-git-project" : "", 65536, 100, 65536), gitInput,
                    reviewer);
            var result = handle.completion().toCompletableFuture().get(90, TimeUnit.SECONDS);
            assertEquals(schema ? Outcome.FAILED : Outcome.CANCELLED, result.outcome(), result.toString());
            if (schema)
                assertEquals("DEPLOYMENT_SCHEMA_REVIEW_REQUIRED", result.problem().orElseThrow().eventCode());
            assertEquals(1, reviewed.get());
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
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    private static <T> OperationResult<T> cancelled() {
        return new OperationResult<>(Outcome.CANCELLED, Optional.empty(), Optional.of(new Problem(ProblemCode.CANCELLED,
                "DEPLOYMENT_STATIC_TEST_REFUSED", "Original review was refused", Map.of(), Optional.empty())));
    }
}
