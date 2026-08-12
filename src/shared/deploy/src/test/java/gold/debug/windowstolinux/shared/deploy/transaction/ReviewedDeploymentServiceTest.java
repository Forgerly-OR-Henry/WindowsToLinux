package gold.debug.windowstolinux.shared.deploy.transaction;

import gold.debug.windowstolinux.shared.config.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.deploy.plan.DeploymentApproval;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentResult;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.linux.transfer.UploadReceipt;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidence;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.SourceRevision;
import gold.debug.windowstolinux.shared.model.server.ServerCapabilities;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;
import gold.debug.windowstolinux.shared.model.server.LinuxDistro;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReviewedDeploymentServiceTest {
    private static final String SHA = "a".repeat(64);
    @TempDir Path temporaryDirectory;

    @Test
    void drivesEveryReviewedProjectTypeThroughOneBoundedTransaction() {
        EnumSet<DeploymentProjectType> built = EnumSet.noneOf(DeploymentProjectType.class);
        DeploymentRemoteSession session = fakeSession(built);
        DeploymentLinuxGateway gateway = (endpoint, credential, verifier) -> session;
        for (DeploymentRuntimeSpecification runtime : runtimes()) {
            DeploymentResult result = new ReviewedDeploymentService().deploy(request(runtime), application(), gateway,
                    new SshEndpoint("server-one", "example.test", 22, "deployer"),
                    new SshCredential.Password("password".toCharArray()), (endpoint, fingerprint) -> HostKeyDecision.ACCEPT_EXISTING);
            assertEquals(DeploymentStatus.SUCCEEDED, result.status());
            assertEquals(SHA, result.publishedArtifactSha256().orElseThrow());
        }
        assertEquals(EnumSet.allOf(DeploymentProjectType.class), built);
    }

    private DeploymentRemoteSession fakeSession(EnumSet<DeploymentProjectType> built) {
        return (DeploymentRemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DeploymentRemoteSession.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "collectCapabilities" -> new ServerCapabilities("Ubuntu 24.04", "x86_64", true, true, true,
                            true, true, true, true, true, 10L * 1024 * 1024 * 1024, "fixture");
                    case "collectDeploymentCapabilities" -> new LinuxCapabilities(LinuxDistro.UBUNTU, "24.04", "x86_64", "apt",
                            true, true, true, true, java.util.Set.of("sse4_2"), "fixture");
                    case "uploadSource" -> {
                        SourceArchiveDescriptor archive = (SourceArchiveDescriptor) arguments[0];
                        RemoteWorkspace workspace = (RemoteWorkspace) arguments[1];
                        yield new UploadReceipt(workspace.candidateRoot() + "/mutable/source.tar.gz", archive.byteCount(),
                                archive.contentSha256(), "fixture upload");
                    }
                    case "buildDeployment" -> {
                        DeploymentProjectFacts facts = (DeploymentProjectFacts) arguments[0];
                        built.add(facts.projectType());
                        yield DeploymentBuildResult.succeeded(SHA, "fixture build");
                    }
                    case "snapshotDeployment" -> ReleaseSnapshot.firstDeployment("fixture snapshot");
                    case "publishDeployment", "cleanupCandidate", "retainRecentSuccessfulReleases", "rollbackDeployment" ->
                            new RemoteStepResult(true, false, "fixture step");
                    case "checkDeploymentHealth" -> new HealthCheckResult(true, "fixture health");
                    case "observeDeployment", "executeDeploymentLifecycle" -> new LifecycleObservation(application(), RuntimeState.RUNNING,
                            AutostartState.DISABLED, true, Instant.now(), "fixture observation");
                    case "close" -> null;
                    case "toString" -> "fixture session";
                    default -> throw new AssertionError("unexpected remote capability: " + method.getName());
                });
    }

    private ReviewedDeploymentRequest request(DeploymentRuntimeSpecification runtime) {
        DeploymentProjectFacts facts = new DeploymentProjectFacts(temporaryDirectory, "demo", runtime.projectType(), tool(runtime),
                List.of(new AnalysisEvidence(LocalizedMessage.of("test.evidence"), "fixture",
                        LocalizedMessage.of("test.detected"), EvidenceConfidence.HIGH)), List.of(), List.of());
        ConfigurationSnapshot configuration = ConfigurationSnapshot.create("demo", 1, "v1", Instant.parse("2026-08-12T00:00:00Z"),
                List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME, new ConfigurationValue.Number(8080))));
        Optional<UserAccessUrl> url = runtime.healthCheck() instanceof HealthCheck.Http
                ? Optional.of(new UserAccessUrl(URI.create("https://example.test/"))) : Optional.empty();
        return new ReviewedDeploymentRequest(application().server(), facts, new SourceRevision(SHA, Optional.empty(), Map.of()),
                new SourceArchiveDescriptor(temporaryDirectory.resolve(runtime.projectType().name() + ".tar.gz"), SHA, 100, 100),
                configuration, List.of(), runtime, url, BuildLimits.defaultNonRoot(),
                new DeploymentApproval("demo", SHA, "server-one", false, Instant.now()), true);
    }

    private static DeploymentBuildTool tool(DeploymentRuntimeSpecification runtime) {
        return switch (runtime.projectType()) {
            case GRADLE_SPRING_BOOT -> DeploymentBuildTool.GRADLE_WRAPPER;
            case JAVA_JAR -> DeploymentBuildTool.JAVA;
            case NODE_SERVICE -> DeploymentBuildTool.NPM;
            case PYTHON_SERVICE -> DeploymentBuildTool.PYTHON_VENV;
            case STATIC_SITE -> DeploymentBuildTool.STATIC_SITE_BUILD;
            case DOCKERFILE_CONTAINER -> DeploymentBuildTool.CONTAINER_BUILD;
        };
    }

    private static List<DeploymentRuntimeSpecification> runtimes() {
        return List.of(
                new DeploymentRuntimeSpecification.GradleSpringBoot(new HealthCheck.Tcp(8080, 5, 1)),
                new DeploymentRuntimeSpecification.JavaJar("app.jar", "demo.Main", "21", List.of("-Xmx256m"), List.of(),
                        new HealthCheck.Tcp(8080, 5, 1)),
                new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(8080, 5, 1)),
                new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", new HealthCheck.Tcp(8080, 5, 1)),
                new DeploymentRuntimeSpecification.StaticSite("public", new HealthCheck.Http(URI.create("http://127.0.0.1:8080/"), 200, 5)),
                new DeploymentRuntimeSpecification.Container(DeploymentRuntimeSpecification.ContainerEngine.PODMAN,
                        Map.of(8080, 8080), List.of(), new HealthCheck.Tcp(8080, 5, 1))
        );
    }

    private static ManagedApplication application() {
        return ManagedApplication.forManaged("demo",
                new ServerIdentity("server-one", "example.test", 22, "SHA256:fixture"), SHA);
    }
}
