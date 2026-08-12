package gold.debug.windowstolinux.shared.deploy.transaction;

import gold.debug.windowstolinux.shared.config.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;
import gold.debug.windowstolinux.shared.deploy.lifecycle.MultiComponentLifecycleService;
import gold.debug.windowstolinux.shared.deploy.plan.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.plan.DeploymentApproval;
import gold.debug.windowstolinux.shared.deploy.plan.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.ComponentTransactionState;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.linux.transfer.UploadReceipt;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidence;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.ApplicationAutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.ApplicationRuntimeState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.SourceRevision;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistro;
import gold.debug.windowstolinux.shared.model.server.LinuxFirewallKind;
import gold.debug.windowstolinux.shared.model.server.LinuxFirewallState;
import gold.debug.windowstolinux.shared.model.server.LinuxSecurityModule;
import gold.debug.windowstolinux.shared.model.server.LinuxSecurityPosture;
import gold.debug.windowstolinux.shared.model.server.LinuxSecurityState;
import gold.debug.windowstolinux.shared.model.server.ServerCapabilities;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewedMultiComponentDeploymentServiceTest {
    private static final ServerIdentity SERVER = new ServerIdentity(
            "server-one", "example.test", 22, "SHA256:fixture-host-key");
    private static final List<String> IDS = List.of("database", "api", "web");

    @TempDir Path temporaryDirectory;

    @Test
    void buildsAndSnapshotsEveryComponentBeforeSwitchingInDependencyOrder() {
        Fixture fixture = new Fixture(null, null);

        var result = deploy(fixture);

        assertEquals(DeploymentStatus.SUCCEEDED, result.status());
        assertTrue(result.applicationReleaseIdentity().orElseThrow().matches("[0-9a-f]{64}"));
        assertTrue(result.componentResults().stream()
                .allMatch(component -> component.state() == ComponentTransactionState.SUCCEEDED));
        assertBeforeAll(fixture.log, "build:shop-web", "snapshot:shop-web");
        assertBeforeAll(fixture.log, "snapshot:shop-database", "stop:shop-web");
        assertOrdered(fixture.log, List.of("stop:shop-web", "stop:shop-api", "stop:shop-database"));
        assertOrdered(fixture.log, List.of("publish:shop-database", "publish:shop-api", "publish:shop-web"));
    }

    @Test
    void restoresEveryOldComponentInReverseOrderWhenMiddleHealthFails() {
        Fixture fixture = new Fixture("shop-api", null);

        var result = deploy(fixture);

        assertEquals(DeploymentStatus.FAILED_ROLLED_BACK, result.status());
        assertTrue(result.componentResults().stream()
                .allMatch(component -> component.state() == ComponentTransactionState.RESTORED));
        assertOrdered(fixture.log, List.of("rollback:shop-web", "rollback:shop-api", "rollback:shop-database"));
        assertEquals(3, fixture.log.stream().filter(value -> value.startsWith("observe:")).count());
    }

    @Test
    void exposesManualRecoveryWhenAnyComponentRollbackFails() {
        Fixture fixture = new Fixture("shop-api", "shop-api");

        var result = deploy(fixture);

        assertEquals(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, result.status());
        assertEquals(ComponentTransactionState.MANUAL_RECOVERY_REQUIRED, result.componentResults().stream()
                .filter(component -> component.componentId().equals("api")).findFirst().orElseThrow().state());
        assertTrue(result.componentResults().stream().filter(component -> !component.componentId().equals("api"))
                .allMatch(component -> component.state() == ComponentTransactionState.RESTORED));
    }

    @Test
    void rejectsAComponentStopThatWouldBreakRunningDependents() {
        LifecycleFixture fixture = new LifecycleFixture();

        var result = lifecycle(fixture, Set.of("database"), LifecycleAction.STOP);

        assertTrue(!result.accepted());
        assertTrue(fixture.log.stream().noneMatch(value -> value.startsWith("execute:")));
        assertEquals(ApplicationRuntimeState.RUNNING, result.runtimeState());
    }

    @Test
    void stopsTheWholeApplicationInReverseOrderFromLiveState() {
        LifecycleFixture fixture = new LifecycleFixture();

        var result = lifecycle(fixture, Set.copyOf(IDS), LifecycleAction.STOP);

        assertTrue(result.accepted());
        assertEquals(ApplicationRuntimeState.STOPPED, result.runtimeState());
        assertOrdered(fixture.log, List.of("execute:STOP:shop-web", "execute:STOP:shop-api",
                "execute:STOP:shop-database"));
    }

    @Test
    void reportsPartialAutostartWithoutCollapsingItToEnabled() {
        LifecycleFixture fixture = new LifecycleFixture();
        fixture.autostart.put("shop-database", AutostartState.ENABLED);

        var result = lifecycle(fixture, Set.of(), LifecycleAction.REFRESH_STATUS);

        assertTrue(result.accepted());
        assertEquals(ApplicationAutostartState.PARTIALLY_ENABLED, result.autostartState());
        assertTrue(result.componentResults().stream().noneMatch(component -> component.actionAttempted()));
    }

    @Test
    void preservesPartialRuntimeTruthWhenAStartFails() {
        LifecycleFixture fixture = new LifecycleFixture();
        fixture.runtime.replaceAll((ignored, state) -> RuntimeState.STOPPED);
        fixture.failedApplicationId = "shop-api";

        var result = lifecycle(fixture, Set.copyOf(IDS), LifecycleAction.START);

        assertTrue(!result.accepted());
        assertEquals(ApplicationRuntimeState.PARTIALLY_RUNNING, result.runtimeState());
        assertOrdered(fixture.log, List.of("execute:START:shop-database", "execute:START:shop-api"));
        assertTrue(fixture.log.stream().noneMatch(value -> value.equals("execute:START:shop-web")));
    }

    private gold.debug.windowstolinux.shared.deploy.result.MultiComponentLifecycleResult lifecycle(
            LifecycleFixture fixture, Set<String> targets, LifecycleAction action) {
        DeploymentLinuxGateway gateway = (endpoint, credential, verifier) -> fixture.session();
        return new MultiComponentLifecycleService().execute(plan(), components(), targets, action, gateway,
                new SshEndpoint("server-one", "example.test", 22, "deployer"),
                new SshCredential.Password("fixture-password".toCharArray()),
                (endpoint, fingerprint) -> HostKeyDecision.ACCEPT_EXISTING);
    }

    private gold.debug.windowstolinux.shared.deploy.result.MultiComponentDeploymentResult deploy(Fixture fixture) {
        DeploymentLinuxGateway gateway = (endpoint, credential, verifier) -> fixture.session();
        return new ReviewedMultiComponentDeploymentService().deploy(plan(), components(),
                new ApplicationHealthGate("web", new HealthCheck.Http(
                        URI.create("http://127.0.0.1:8082/ready"), 200, 5)), gateway,
                new SshEndpoint("server-one", "example.test", 22, "deployer"),
                new SshCredential.Password("fixture-password".toCharArray()),
                (endpoint, fingerprint) -> HostKeyDecision.ACCEPT_EXISTING);
    }

    private List<ReviewedComponentDeployment> components() {
        List<ReviewedComponentDeployment> components = new ArrayList<>();
        for (int index = 0; index < IDS.size(); index++) {
            String id = IDS.get(index);
            String managedId = "shop-" + id;
            String sha = Character.toString((char) ('a' + index)).repeat(64);
            int port = 8080 + index;
            DeploymentRuntimeSpecification runtime = new DeploymentRuntimeSpecification.StaticSite(
                    "public", new HealthCheck.Http(URI.create("http://127.0.0.1:" + port + "/health"), 200, 5));
            DeploymentProjectFacts facts = new DeploymentProjectFacts(temporaryDirectory.resolve(id), managedId,
                    DeploymentProjectType.STATIC_SITE, DeploymentBuildTool.STATIC_SITE_BUILD,
                    List.of(new AnalysisEvidence(LocalizedMessage.of("test.evidence"), "fixture",
                            LocalizedMessage.of("test.detected"), EvidenceConfidence.HIGH)), List.of(), List.of());
            ReviewedDeploymentRequest request = new ReviewedDeploymentRequest(SERVER, facts,
                    new SourceRevision(sha, Optional.empty(), Map.of()),
                    new SourceArchiveDescriptor(temporaryDirectory.resolve(id + ".tar.gz"), sha, 100, 100),
                    ConfigurationSnapshot.create(managedId, 1, "v1", Instant.parse("2026-08-13T00:00:00Z"),
                            List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME,
                                    new ConfigurationValue.Number(port)))),
                    List.of(), runtime, Optional.of(new UserAccessUrl(
                            URI.create("https://" + managedId + ".example.test/"))), BuildLimits.defaultNonRoot(),
                    new DeploymentApproval(managedId, sha, SERVER.id(), false, Instant.parse("2026-08-13T00:00:00Z")),
                    false);
            components.add(new ReviewedComponentDeployment(id, request,
                    ManagedApplication.forManaged(managedId, SERVER, sha), List.of()));
        }
        return components;
    }

    private static MultiComponentDeploymentPlan plan() {
        Map<String, String> namespaces = new LinkedHashMap<>();
        namespaces.put("database", "shop-database");
        namespaces.put("api", "shop-api");
        namespaces.put("web", "shop-web");
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        dependencies.put("database", List.of());
        dependencies.put("api", List.of("database"));
        dependencies.put("web", List.of("api"));
        return new MultiComponentDeploymentPlan("shop",
                List.of(List.of("database"), List.of("api"), List.of("web")),
                List.of("web", "api", "database"), IDS, IDS, List.of("web", "api", "database"), namespaces,
                dependencies);
    }

    private static void assertOrdered(List<String> actual, List<String> expected) {
        int previous = -1;
        for (String value : expected) {
            int current = actual.indexOf(value);
            assertTrue(current > previous, () -> value + " was not ordered in " + actual);
            previous = current;
        }
    }

    private static void assertBeforeAll(List<String> actual, String earlier, String later) {
        assertTrue(actual.indexOf(earlier) >= 0 && actual.indexOf(earlier) < actual.indexOf(later),
                () -> earlier + " must precede " + later + " in " + actual);
    }

    private final class LifecycleFixture {
        private final List<String> log = new ArrayList<>();
        private final Map<String, RuntimeState> runtime = new LinkedHashMap<>();
        private final Map<String, AutostartState> autostart = new LinkedHashMap<>();
        private String failedApplicationId;

        private LifecycleFixture() {
            IDS.forEach(id -> {
                runtime.put("shop-" + id, RuntimeState.RUNNING);
                autostart.put("shop-" + id, AutostartState.DISABLED);
            });
        }

        private DeploymentRemoteSession session() {
            return (DeploymentRemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{DeploymentRemoteSession.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "observeDeployment" -> observe((ManagedApplication) arguments[0]);
                        case "executeDeploymentLifecycle" -> execute((ManagedApplication) arguments[0],
                                (LifecycleAction) arguments[2]);
                        case "close" -> null;
                        case "toString" -> "multi-component lifecycle fixture session";
                        default -> throw new AssertionError("unexpected lifecycle operation: " + method.getName());
                    });
        }

        private LifecycleObservation execute(ManagedApplication application, LifecycleAction action) {
            log.add("execute:" + action + ":" + application.id());
            if (!application.id().equals(failedApplicationId)) {
                switch (action) {
                    case START, RESTART -> runtime.put(application.id(), RuntimeState.RUNNING);
                    case STOP -> runtime.put(application.id(), RuntimeState.STOPPED);
                    case ENABLE_AUTOSTART -> autostart.put(application.id(), AutostartState.ENABLED);
                    case DISABLE_AUTOSTART -> autostart.put(application.id(), AutostartState.DISABLED);
                    case REFRESH_STATUS -> throw new AssertionError("refresh must not execute a lifecycle mutation");
                }
            }
            return observe(application);
        }

        private LifecycleObservation observe(ManagedApplication application) {
            log.add("observe:" + application.id());
            return new LifecycleObservation(application, runtime.get(application.id()), autostart.get(application.id()),
                    true, Instant.parse("2026-08-13T00:00:00Z"), "fixture live observation");
        }
    }

    private final class Fixture {
        private final List<String> log = new ArrayList<>();
        private final String unhealthyId;
        private final String rollbackFailureId;

        private Fixture(String unhealthyId, String rollbackFailureId) {
            this.unhealthyId = unhealthyId;
            this.rollbackFailureId = rollbackFailureId;
        }

        private DeploymentRemoteSession session() {
            return (DeploymentRemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{DeploymentRemoteSession.class}, (proxy, method, arguments) -> {
                        String name = method.getName();
                        return switch (name) {
                            case "collectCapabilities" -> new ServerCapabilities("Ubuntu 24.04", "x86_64", true,
                                    true, true, true, true, true, true, true, ManagedHelperProtocol.VERSION,
                                    32L * 1024 * 1024 * 1024, "fixture");
                            case "collectDeploymentCapabilities" -> new LinuxCapabilities(LinuxDistro.UBUNTU, "24.04",
                                    "x86_64", "apt", "amd64", true, true, true, true, java.util.Set.of(21),
                                    java.util.Set.of(22), true, true, java.util.Set.of("3.12"), true, Map.of(),
                                    true, true, CpuMicroarchitectureLevel.X86_64_V3, java.util.Set.of("sse4_2"),
                                    new LinuxSecurityPosture(LinuxSecurityModule.APPARMOR, LinuxSecurityState.ENABLED,
                                            LinuxFirewallKind.UFW, LinuxFirewallState.ACTIVE), "fixture");
                            case "uploadSource" -> upload((SourceArchiveDescriptor) arguments[0],
                                    (RemoteWorkspace) arguments[1]);
                            case "buildDeployment" -> build((RemoteWorkspace) arguments[2]);
                            case "stageDeploymentInputs" -> stage((ManagedApplication) arguments[0]);
                            case "snapshotDeployment" -> snapshot((ManagedApplication) arguments[0]);
                            case "executeDeploymentLifecycle" -> stop((ManagedApplication) arguments[0],
                                    (LifecycleAction) arguments[2]);
                            case "publishDeployment" -> step("publish", (ManagedApplication) arguments[0], true);
                            case "checkDeploymentHealth" -> health((ManagedApplication) arguments[0]);
                            case "observeDeployment" -> observation("observe", (ManagedApplication) arguments[0],
                                    RuntimeState.RUNNING);
                            case "retainRecentSuccessfulReleases" -> step("retain", (ManagedApplication) arguments[0], true);
                            case "rollbackDeployment" -> rollback((ManagedApplication) arguments[0]);
                            case "observe" -> observation("restore-observe", (ManagedApplication) arguments[0],
                                    RuntimeState.RUNNING);
                            case "cleanupCandidate" -> cleanup((RemoteWorkspace) arguments[0]);
                            case "close" -> null;
                            case "toString" -> "multi-component fixture session";
                            default -> throw new AssertionError("unexpected remote operation: " + name);
                        };
                    });
        }

        private UploadReceipt upload(SourceArchiveDescriptor archive, RemoteWorkspace workspace) {
            log.add("upload:" + workspace.applicationId());
            return new UploadReceipt(workspace.candidateRoot() + "/source.tar.gz", archive.byteCount(),
                    archive.contentSha256(), "fixture upload");
        }

        private DeploymentBuildResult build(RemoteWorkspace workspace) {
            log.add("build:" + workspace.applicationId());
            return DeploymentBuildResult.succeeded(workspace.sourceSha256(), "fixture build");
        }

        private DeploymentInputManifest stage(ManagedApplication application) {
            log.add("stage:" + application.id());
            return new DeploymentInputManifest(application.ownershipManifestSha256(), List.of());
        }

        private ReleaseSnapshot snapshot(ManagedApplication application) {
            log.add("snapshot:" + application.id());
            return ReleaseSnapshot.withPreviousRelease("token-" + application.id(), true, "fixture snapshot");
        }

        private LifecycleObservation stop(ManagedApplication application, LifecycleAction action) {
            assertEquals(LifecycleAction.STOP, action);
            return observation("stop", application, RuntimeState.STOPPED);
        }

        private RemoteStepResult rollback(ManagedApplication application) {
            boolean succeeded = !application.id().equals(rollbackFailureId);
            return step("rollback", application, succeeded);
        }

        private RemoteStepResult cleanup(RemoteWorkspace workspace) {
            log.add("cleanup:" + workspace.applicationId());
            return new RemoteStepResult(true, false, "fixture cleanup");
        }

        private RemoteStepResult step(String operation, ManagedApplication application, boolean succeeded) {
            log.add(operation + ":" + application.id());
            return new RemoteStepResult(succeeded, false, "fixture " + operation);
        }

        private HealthCheckResult health(ManagedApplication application) {
            log.add("health:" + application.id());
            return new HealthCheckResult(!application.id().equals(unhealthyId), "fixture health");
        }

        private LifecycleObservation observation(String operation, ManagedApplication application, RuntimeState state) {
            log.add(operation + ":" + application.id());
            return new LifecycleObservation(application, state, AutostartState.DISABLED, true,
                    Instant.parse("2026-08-13T00:00:00Z"), "fixture " + operation);
        }
    }
}
