package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferCheckpoint;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentPreparedDatabase;
import org.junit.jupiter.api.Test;

/** Rejects actual reviewed overlapping graph ports before original remote admission. / 原远端准入前拒绝实际审阅的整图端口重叠。 */
class DeploymentConflictingPortPublishTest extends DeploymentPublishAcceptanceFixture {
    @Test
    void originalClassTaskRejectsConflictingReviewedPortsBeforeAnyRemoteEffect() throws Exception {
        source(2);
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile
                    .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var original = runtime.modContexts().get(OWNER).context();
            var taskId = new AtomicReference<UUID>();
            var reviewed = new AtomicInteger();
            var connects = new AtomicInteger();
            LinuxService linux = new LinuxService() {
                public RemoteSession connect(ServerId server, TaskContext task) {
                    connects.incrementAndGet();
                    throw new AssertionError(
                        "Port collision must precede helper, toolchain, candidate, build and publication effects");
                }

                public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
                    throw new AssertionError("Port collision needs no transfer workflow");
                }
            };
            var bound = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("linux"))
                        return linux;
                    try {
                        return method.invoke(original, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var defaults = reviewer(2);
            var conflict = new DeploymentStaticWorkflow.Reviewer() {
                public OperationResult<DeploymentStaticWorkflow.Selection> select(ServerId server, String sha,
                                                                                  List<DiscoveredProjectPart> components, TaskContext task) throws Exception {
                    assertTrue(taskId.compareAndSet(null, task.id()));
                    assertEquals(new ServerId("server"), server);
                    assertTrue(sha.matches("[0-9a-f]{64}"));
                    return defaults.select(server, sha, components, task);
                }

                public OperationResult<Map<String, DeploymentStaticWorkflow.Answers>> review(ServerId server,
                                                                                             String sha, List<DeploymentStaticWorkflow.Prompt> prompts, TaskContext task) throws Exception {
                    assertEquals(taskId.get(), task.id());
                    assertEquals(2, prompts.size());
                    reviewed.incrementAndGet();
                    assertTrue(scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + task.id()
                        + "' AND resource_kind='FILE' AND released_at IS NULL") > 0);
                    var answers = defaults.review(server, sha, prompts, task).value().orElseThrow();
                    var colliding = new LinkedHashMap<String, DeploymentStaticWorkflow.Answers>();
                    answers.forEach((id, answer) -> {
                        var values = new LinkedHashMap<>(answer.values());
                        values.put("port", "8080");
                        colliding.put(id, new DeploymentStaticWorkflow.Answers(values, answer.files(), answer.limits(),
                            answer.containerRisk(), answer.adapterRisk()));
                    });
                    assertEquals(2, colliding.size());
                    colliding.values().forEach(answer -> assertEquals("8080", answer.values().get("port")));
                    return success(colliding);
                }

                public OperationResult<Boolean> confirm(ServerId server, String sha, Map<String, String> archives,
                                                        Map<String, DeploymentStaticWorkflow.Answers> answers,
                                                        Map<String, DeploymentPreparedDatabase> databases,
                                                        Optional<DeploymentStaticWorkflow.GitReview> git, TaskContext task) {
                    throw new AssertionError("Rejected graph cannot reach final publication confirmation");
                }
            };
            var handle = new DeploymentStaticWorkflow(bound, new DeploymentRefFlow(bound)).submit(
                new ServerId("server"), new DeploymentStaticWorkflow.Draft("", 65536, 100, 65536),
                Optional.empty(), conflict, false, "MANUAL_REVIEW");
            var result = handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TaskResult.FAILED, result.outcome(), result.toString());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(handle.id(), taskId.get());
            assertEquals(1, reviewed.get());
            assertEquals(0, connects.get());
            assertEquals("DEPLOYMENT_STATIC_ANALYSIS_INCOMPLETE", result.problem().orElseThrow().eventCode());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            try (var access = scope(runtime, true);
                 var connection = access.openConnection();
                 var statement = connection.createStatement()) {
                for (String table : List.of("managed_application", "deployment_unit", "deployment_release",
                    "deployment_execution")) {
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getInt(1));
                    }
                }
            }
        }
    }
}
