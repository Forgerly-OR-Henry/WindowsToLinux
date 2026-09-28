package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises frozen-source discovery, edited input and archive confirmation within one real CLASS/H2 task. / 在真实CLASS/H2同一任务中验证冻结来源发现、编辑输入及归档确认。 */
class DeploymentStaticWorkflowTest extends DeploymentRuntimeFixture {
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
        runReview(count, false);
    }

    /** Source schema mutations reach the review form, then stop before archive or remote effect without verified DB work. / 源码模式变更可进入审阅表单，但缺少已验证数据库操作时在归档与远端效果前停止。
     * @throws Exception if real source or task setup fails / 真实来源或任务设置失败时 */
    @Test
    void schemaMutationNeedsSeparateVerifiedReviewAfterRuntimeForm() throws Exception {
        runReview(1, true);
    }

    private void runReview(int count, boolean schema) throws Exception {
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
                        List<DiscoveredProjectComponent> discovered, TaskContext task) {
                    assertEquals(count, discovered.size());
                    taskId.set(task.id());
                    source.set(sha);
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
                        Map<String, DeploymentStaticWorkflow.Answers> answers, TaskContext task) {
                    assertFalse(schema, "Schema review must block before final publication consent");
                    assertEquals(taskId.get(), task.id());
                    assertEquals(source.get(), sha);
                    assertEquals(count, archiveSha.size());
                    assertEquals(archiveSha.keySet(), answers.keySet());
                    archiveSha.values().forEach(value -> assertTrue(value.matches("[a-f0-9]{64}")));
                    archives.set(Map.copyOf(archiveSha));
                    return cancelled();
                }
            };
            var handle = workflow.submit(new ServerRef("server"),
                    new DeploymentStaticWorkflow.Draft("", 65536, 100, 65536), reviewer);
            var result = handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(schema ? Outcome.FAILED : Outcome.CANCELLED, result.outcome(), result.toString());
            if (schema)
                assertEquals("DEPLOYMENT_SCHEMA_REVIEW_REQUIRED", result.problem().orElseThrow().eventCode());
            assertEquals(1, reviewed.get());
            if (!schema)
                assertEquals(count, archives.get().size());
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
        }
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    private static <T> OperationResult<T> cancelled() {
        return new OperationResult<>(Outcome.CANCELLED, Optional.empty(), Optional.of(new Problem(ProblemCode.CANCELLED,
                "DEPLOYMENT_STATIC_TEST_REFUSED", "Original review was refused", Map.of(), Optional.empty())));
    }
}
