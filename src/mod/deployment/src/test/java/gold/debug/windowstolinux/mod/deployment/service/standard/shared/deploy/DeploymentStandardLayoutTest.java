package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionIdentity;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionRecord;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.report.DeploymentProjectReport;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentFileBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStorageLocation;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStoragePath;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.DeploymentStandardCandidateRunner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentReviewConsent;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentStoragePlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.DeploymentPublishRecoveryFacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uses real review, candidate admission and recovery parsers with controlled public capability facts. */
class DeploymentStandardLayoutTest {
    private static final UUID TASK = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final ServerId SERVER = new ServerId("server-layout-523");
    private static final String SOURCE_SHA = "9".repeat(64);
    private static final DeploymentRemoteLayout LAYOUT = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
        "/srv/wtl/helper", "/srv/wtl/apps", "/srv/wtl/state", "/srv/wtl/config", "/srv/wtl/data"));
    private static final DeploymentBuildLimits LIMITS = new DeploymentBuildLimits(120, 32, 512, 65536,
        64L * 1024 * 1024, false);
    @TempDir Path local;

    @Test
    void reviewBuilderBindsRealRequestStorageAndRenderedCandidateToExplicitLayout() throws Exception {
        var plan = plan(LAYOUT);
        assertEquals(LAYOUT, plan.request().layout());
        var physical = DeploymentStoragePlan.resolve("demo", plan.request().resources(), plan.request().runtime(), LAYOUT);
        assertEquals("/srv/wtl/data/demo/files/uploads", physical.getFirst().physicalPath());
        var rendered = new DeploymentStandardPlanner().render(plan, task(), LAYOUT);
        assertEquals(TaskResult.SUCCEEDED, rendered.outcome());
        assertTrue(rendered.value().orElseThrow().buildScript().contains("candidate='/srv/wtl/state/work/demo-"));
        assertFalse(rendered.value().orElseThrow().buildScript().contains("/var/lib/windowstolinux/work/"));
        var original = plan(DeploymentRemoteLayout.original());
        assertEquals(DeploymentRemoteLayout.original(), original.request().layout());
        assertNotEquals(rendered.value().orElseThrow().reviewedPlanSha256(),
            new DeploymentStandardPlanner().render(original, task()).value().orElseThrow().reviewedPlanSha256());
    }

    @Test
    void candidateRunnerRejectsLayoutDriftBeforeAnyExternalApprovalOrTransport() throws Exception {
        var plan = plan(LAYOUT);
        var wrong = new DeploymentRemoteWorkspace("demo", plan.request().archive().sha256());
        var session = unavailable(RemoteSession.class);
        var interaction = unavailable(InteractionService.class);
        assertThrows(IllegalArgumentException.class, () -> new DeploymentStandardCandidateRunner().prepare(plan,
            session, interaction, task(), false, 0, new DeploymentBuildBudget(120), wrong));
    }

    @Test
    void recoveryParserRequiresCapturedLayoutAndExactOriginalReleaseRoot() {
        String archive = "a".repeat(64), reviewed = "e".repeat(64), owner = "b".repeat(64);
        var identity = new DeploymentExecutionIdentity(UUID.randomUUID(), TASK, Optional.empty(), "demo", SERVER,
            DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, reviewed);
        var record = new DeploymentExecutionRecord(identity, "CANDIDATE_MAY_EXIST", Optional.empty(), "{}", "{}",
            Optional.empty(), false, false, Instant.EPOCH, Instant.EPOCH);
        var unit = DeploymentUnitIdentity.forManaged("demo", SERVER, owner, LAYOUT);
        var node = new ObjectMapper().createObjectNode().put("version", 3).put("task", TASK.toString())
            .put("application", "demo").put("server", SERVER.id()).put("ownerManifestSha256", owner)
            .put("archiveSha256", archive).put("candidate", new DeploymentRemoteWorkspace("demo", archive, LAYOUT).candidateId())
            .put("planSha256", reviewed).put("phase", "CANDIDATE_MAY_EXIST").put("backend", "native")
            .put("engine", "none").put("releaseRoot", unit.releaseRoot());
        assertEquals(unit, DeploymentPublishRecoveryFacts.read(node, record, LAYOUT).unit());
        assertThrows(IllegalArgumentException.class, () -> DeploymentPublishRecoveryFacts.read(node, record));
        node.put("releaseRoot", "/opt/windowstolinux/apps/demo");
        assertThrows(IllegalArgumentException.class, () -> DeploymentPublishRecoveryFacts.read(node, record, LAYOUT));
    }

    private ReviewedDeploymentPlan plan(DeploymentRemoteLayout layout) throws Exception {
        Files.writeString(local.resolve("main.go"), "package main\nfunc main() {}\n");
        byte[] bytes = "controlled source archive".getBytes(StandardCharsets.UTF_8);
        Files.write(local.resolve("source.tar.gz"), bytes);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var material = new DeploymentSourceMaterial("source", snapshot(), List.of(), List.of());
        var archive = new DeploymentSourceArchive(snapshot(), digest, bytes.length, bytes.length, TASK, SOURCE_SHA, "");
        var facts = new DeploymentProjectFacts(local, "demo", DeploymentProjectKind.GO_SERVICE,
            DeploymentBuildToolType.GO_MODULE, List.of(), List.of(), List.of());
        var report = new DeploymentProjectReport(DeploymentAdmissionStatus.READY_FOR_PLANNING,
            Optional.of(facts), Optional.empty(), List.of());
        var analysis = new DeploymentStandardAnalyzer.SnapshotReport<>(TASK, SOURCE_SHA, report);
        var answers = new DeploymentStandardReviewAnswers(Map.ofEntries(Map.entry("primary", "demo"),
            Map.entry("secondary", "main.go"), Map.entry("version", "1.24"), Map.entry("healthMode", "TCP"),
            Map.entry("port", "8080"), Map.entry("exposure", "INTERNAL"), Map.entry("databaseMode", "NONE")),
            List.of(new DeploymentFileBinding("uploads", new DeploymentStoragePath("uploads",
                DeploymentStoragePath.AccessMode.READ_WRITE, "files", true), DeploymentStorageLocation.defaults(),
                DeploymentStorageLocation.StorageResourceType.FILE)), 1, LIMITS,
            new DeploymentReviewConsent("demo", SERVER.id(), SOURCE_SHA, digest, Instant.EPOCH), false, true);
        ServerService servers = (ServerService) Proxy.newProxyInstance(ServerService.class.getClassLoader(),
            new Class<?>[]{ServerService.class}, (proxy, method, args) -> {
                assertEquals("get", method.getName());
                assertEquals(SERVER, args[0]);
                return new ServerInfo(SERVER, "fixture", "127.0.0.1", 22, "fixture", Optional.empty(), Optional.empty());
            });
        var builder = layout.equals(DeploymentRemoteLayout.original())
            ? new DeploymentStandardReviewBuilder(servers, new DeploymentStandardPlanner())
            : new DeploymentStandardReviewBuilder(servers, new DeploymentStandardPlanner(), layout);
        var planned = builder.plan(SERVER, material, archive, analysis, answers, task());
        assertEquals(TaskResult.SUCCEEDED, planned.outcome(), planned.toString());
        return planned.value().orElseThrow();
    }

    private FileSnapshot snapshot() {
        return new FileSnapshot() {
            public UUID id() { return TASK; }
            public String sha256() { return SOURCE_SHA; }
            public Path directory() { return local; }
            public void close() { }
        };
    }

    private static TaskContext task() {
        return (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(), new Class<?>[]{TaskContext.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "id" -> TASK;
                case "cancelToken" -> (CancelToken) () -> false;
                default -> throw new AssertionError("Unexpected task call: " + method.getName());
            });
    }

    private static <T> T unavailable(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, args) -> { throw new AssertionError("No external call before layout admission: " + method.getName()); }));
    }
}
