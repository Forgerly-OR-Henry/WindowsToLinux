package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionIdentity;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublishCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentApplicationGraph;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitRuntimeConfig;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentCommand;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentEndpoint;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentWorker;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentWorkload;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishPlan;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;

/** Real original H2 publication identities and reviewed public usage fixtures. / 实际原H2发布身份与已审阅公开用法夹具。 */
public abstract class DeploymentUsageFixture extends DeploymentApplicationsFixture {
    public static final String STARTUP = "STARTUP_ARGUMENT_SENTINEL", CLIENT = "CLIENT_ARGUMENT_SENTINEL", WORKER = "WORKER_ARGUMENT_SENTINEL";

    public void usageServer(PlatformRuntime runtime) throws Exception {
        runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile("server", "2001:db8::1", 22,
            "tester", "fixture-secret")), "fixture-secret".toCharArray());
    }

    public DeploymentApplicationGraph usageGraph(String application, boolean onDemandOnly, boolean reviewed) {
        var parts = new ArrayList<DeploymentApplicationGraph.Part>();
        for (String id : onDemandOnly ? List.of("job") : List.of("service", "client", "job")) {
            var original = component(id, "server", List.of());
            boolean job = id.equals("job");
            var endpoints = id.equals("service") ? List.of(
                new DeploymentEndpoint("http", DeploymentEndpoint.ProtocolType.HTTP, "::", 8080, 8080, DeploymentEndpoint.ExposureType.EXTERNAL, ""),
                new DeploymentEndpoint("https", DeploymentEndpoint.ProtocolType.HTTPS, "::", 8443, 8443, DeploymentEndpoint.ExposureType.EXTERNAL, "https://demo.debug.gold/app"),
                new DeploymentEndpoint("tcp", DeploymentEndpoint.ProtocolType.TCP, "::", 9000, 9000, DeploymentEndpoint.ExposureType.EXTERNAL, ""),
                new DeploymentEndpoint("udp", DeploymentEndpoint.ProtocolType.UDP, "::", 9001, 9001, DeploymentEndpoint.ExposureType.EXTERNAL, "")) : List.<DeploymentEndpoint>of();
            var workload = new DeploymentWorkload(job ? DeploymentWorkload.ExecutionMode.ON_DEMAND : DeploymentWorkload.ExecutionMode.DAEMON,
                reviewed, new DeploymentCommand("app.js", List.of(STARTUP)), "", endpoints,
                job ? Optional.of(new DeploymentCommand("verify.js", List.of())) : Optional.empty(), "",
                id.equals("client") ? Optional.of(new DeploymentCommand("client.js", List.of(CLIENT))) : Optional.empty(),
                List.of(), List.of(), "", id.equals("service") ? List.of(new DeploymentWorker("queue",
                new DeploymentCommand("worker.js", List.of(WORKER)))) : List.of());
            var definition = original.reviewedRuntime().withWorkload(workload);
            var runtime = new DeploymentUnitRuntimeConfig(original.runtime().healthCheck(), Optional.empty(), definition.identityPolicy(), workload);
            parts.add(new DeploymentApplicationGraph.Part(id, DeploymentUnitIdentity.forManaged(application + "-" + id,
                new ServerId("server"), "a".repeat(64)), runtime, List.of(), definition, original.dataPaths(), original.resources()));
        }
        return new DeploymentApplicationGraph(application, onDemandOnly ? "job" : "service", new DeploymentHealthCheck.Process(20, 2), parts);
    }

    public TaskHandle<Void> seedUsage(PlatformRuntime runtime, DeploymentApplicationGraph graph, int revision) throws Exception {
        return seedUsage(runtime, graph, revision, true);
    }

    public TaskHandle<Void> seedUsage(PlatformRuntime runtime, DeploymentApplicationGraph graph, int revision, boolean cleanup) throws Exception {
        var handle = rootTask(runtime, task -> {
            publishUsage(runtime, graph, revision, cleanup, task);
            return success();
        });
        assertEquals(TaskResult.SUCCEEDED, await(handle).outcome());
        return handle;
    }

    /** Persists canonical fixture publication within its actual admitted scope. / 在真实准入作用域内持久化规范夹具发布。
     * @param runtime actual isolated platform / 实际隔离平台
     * @param graph reviewed fixture graph / 经审阅夹具图
     * @param revision fixed fixture revision / 固定夹具修订
     * @param cleanup whether original business cleanup is confirmed / 是否确认原业务收尾
     * @param task authentic accepted task, never a replacement context / 真实接纳任务，绝不是替代上下文
     * @throws Exception if original commit cannot be confirmed / 无法确认原提交时 */
    public void publishUsage(PlatformRuntime runtime, DeploymentApplicationGraph graph, int revision, boolean cleanup,
                             TaskContext task) throws Exception {
        var publication = new DeploymentPublishResult(graph, publications(graph, revision, List.of()));
        var plan = new DeploymentPublishPlan(UUID.randomUUID(), Optional.empty(), graph.applicationId(), new ServerId("server"),
            DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "a".repeat(64),
            DeploymentOriginalPublishCommit.references(new ServerId("server"), publication, Map.of()), Instant.now());
        var commit = new DeploymentOriginalPublishCommit(context(runtime));
        commit.begin(plan, publication, task, "deployment-publication");
        assertEquals(TaskResult.SUCCEEDED, commit.commitVerified(task, plan.executionId(), publication).outcome());
        if (cleanup)
            assertEquals(TaskResult.SUCCEEDED, commit.finishKnown(task,
                new DeploymentExecutionStore(context(runtime).db()).find(task, plan.executionId()).orElseThrow()).outcome());
        for (var part : graph.components()) {
            boolean job = part.runtime().workload().mode() == DeploymentWorkload.ExecutionMode.ON_DEMAND;
            sql(runtime, task, "UPDATE deployment_unit SET runtime_state='" + (job ? "INSTALLED" : "RUNNING")
                + "',autostart_state='" + (job ? "NOT_APPLICABLE" : "ENABLED")
                + "',is_ownership_verified=1,observation_evidence='controlled-usage-observation',observed_at=1 WHERE deployment_unit_id='"
                + part.unit().id() + "'");
        }
    }
}
