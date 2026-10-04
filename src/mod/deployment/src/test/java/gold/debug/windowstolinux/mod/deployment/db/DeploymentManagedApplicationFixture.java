package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentPublish;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentPublishPlan;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublishCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentApplicationGraph;

/** Seeds complete managed applications through the actual formal publication contract. / 通过实际正式发布契约建立完整受管应用种子。 */
abstract class DeploymentManagedApplicationFixture extends DeploymentGraphFixture {
    /** Verifies a permanently unknown original task in an owned child without pretending host cleanup. / 在所属子进程核实永久未知原任务，不冒称宿主清理。
     * @param scenario exact original negative scenario / 精确原负例场景
     * @throws Exception if the actual child fails or does not exit / 实际子进程失败或未退出时 */
    void runIsolated(String scenario) throws Exception {
        var executable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        var process = new ProcessBuilder(executable.toString(), "-cp", System.getProperty("java.class.path"),
            getClass().getName(), "--unknown", root.toString(), scenario).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(50, TimeUnit.SECONDS), "Original unknown child timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("MANAGED_APPLICATION_UNKNOWN_VERIFIED"), output);
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

    /** Commits and closes one real original publication with its complete template and references. / 提交并关闭含完整模板和引用的一项真实原发布。
     * @param runtime actual CLASS platform / 实际CLASS平台
     * @param task authentic original task / 真实原任务
     * @param graph complete original application / 完整原应用
     * @param revision original configuration revision / 原配置修订
     * @return exact immutable formal release / 精确不可变正式发布
     * @throws Exception if the actual original publication fails / 实际原发布失败时 */
    DeploymentReleaseRecord publishManaged(PlatformRuntime runtime, TaskContext task,
                                           DeploymentApplicationGraph graph, int revision) throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        var units = publications(graph, revision, List.of()).stream()
            .sorted(java.util.Comparator.comparing(value -> value.unit().id())).toList();
        var proposal = new DeploymentPublish(graph, units);
        var server = graph.components().getFirst().unit().server();
        var plan = new DeploymentPublishPlan(UUID.randomUUID(), Optional.empty(), graph.applicationId(), server,
            DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "a".repeat(64),
            DeploymentOriginalPublishCommit.references(server, proposal, Map.of()),
            Instant.ofEpochMilli(System.currentTimeMillis()));
        var commit = new DeploymentOriginalPublishCommit(context);
        commit.begin(plan, proposal, task, "deployment-publication");
        assertEquals(TaskResult.SUCCEEDED, commit.commitVerified(task, plan.executionId(), proposal).outcome());
        var record = new DeploymentExecutionStore(context.db()).find(task, plan.executionId()).orElseThrow();
        assertEquals(TaskResult.SUCCEEDED, commit.finishKnown(task, record).outcome());
        var link = DeploymentExecutionCodec.release(record).orElseThrow();
        return new DeploymentReleaseStore(context.db()).find(task, graph.applicationId(), link.id()).orElseThrow();
    }
}
