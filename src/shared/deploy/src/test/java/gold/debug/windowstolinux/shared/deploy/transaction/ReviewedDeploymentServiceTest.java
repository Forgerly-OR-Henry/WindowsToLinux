package gold.debug.windowstolinux.shared.deploy.transaction;

import gold.debug.windowstolinux.shared.config.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;
import gold.debug.windowstolinux.shared.deploy.contract.DeploymentApproval;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedReleaseIdentityResolver;
import gold.debug.windowstolinux.shared.deploy.result.deployment.DeploymentResult;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.linux.transfer.SourceUploadResult;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.SourceRevision;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.capability.EcosystemToolType;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallKind;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallState;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityModuleType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityPosture;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewedDeploymentServiceTest {
    private static final String SHA = "a".repeat(64);
    @TempDir Path temporaryDirectory;

    @Test
    void drivesEveryReviewedProjectTypeThroughOneBoundedTransaction() {
        EnumSet<DeploymentProjectType> built = EnumSet.noneOf(DeploymentProjectType.class);
        DeploymentRemoteSession session = fakeSession(built);
        DeploymentLinuxGateway gateway = (endpoint, credential, verifier) -> session;
        for (DeploymentRuntimeSpecification runtime : runtimes()) {
            ReviewedDeploymentRequest request = request(runtime);
            DeploymentResult result = new ReviewedDeploymentService().deploy(request, application(), gateway,
                    new SshEndpoint("server-one", "example.test", 22, "deployer"),
                    new SshCredential.Password("password".toCharArray()), (endpoint, fingerprint) -> HostKeyDecision.ACCEPT_EXISTING);
            assertEquals(DeploymentStatus.SUCCEEDED, result.status());
            assertEquals(ReviewedReleaseIdentityResolver.from(request), result.publishedReleaseSha256().orElseThrow());
        }
        assertEquals(EnumSet.copyOf(DeploymentProjectType.deployableTypes()), built);
    }

    @Test
    void reconnectsAndCleansTheCandidateWhenInputStagingIsInterrupted() {
        Counters counters = new Counters();
        DeploymentRemoteSession session = fakeSession(EnumSet.noneOf(DeploymentProjectType.class),
                "stageDeploymentInputs", counters);
        DeploymentLinuxGateway gateway = (endpoint, credential, verifier) -> {
            counters.connections.incrementAndGet();
            return session;
        };

        DeploymentResult result = new ReviewedDeploymentService().deploy(request(runtimes().get(2)), application(), gateway,
                new SshEndpoint("server-one", "example.test", 22, "deployer"),
                new SshCredential.Password("password".toCharArray()), (endpoint, fingerprint) -> HostKeyDecision.ACCEPT_EXISTING);

        assertEquals(DeploymentStatus.PRECONDITION_REJECTED, result.status());
        assertEquals(2, counters.connections.get());
        assertEquals(1, counters.cleanups.get());
        assertEquals(0, counters.rollbacks.get());
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals("candidate-cleanup-reconnect")));
    }

    @Test
    void reconnectsAndRollsBackWhenPublicationIsInterrupted() {
        Counters counters = new Counters();
        DeploymentRemoteSession session = fakeSession(EnumSet.noneOf(DeploymentProjectType.class),
                "publishDeployment", counters);
        DeploymentLinuxGateway gateway = (endpoint, credential, verifier) -> {
            counters.connections.incrementAndGet();
            return session;
        };

        DeploymentResult result = new ReviewedDeploymentService().deploy(request(runtimes().getFirst()), application(), gateway,
                new SshEndpoint("server-one", "example.test", 22, "deployer"),
                new SshCredential.Password("password".toCharArray()), (endpoint, fingerprint) -> HostKeyDecision.ACCEPT_EXISTING);

        assertEquals(DeploymentStatus.FAILED_FIRST_DEPLOYMENT, result.status());
        assertEquals(2, counters.connections.get());
        assertEquals(1, counters.rollbacks.get());
        assertEquals(1, counters.cleanups.get());
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals("recovery-reconnect")));
    }

    @Test
    void rejectsAStaleHelperBeforeCreatingOrUploadingACandidate() {
        Counters counters = new Counters();
        DeploymentRemoteSession session = fakeSession(EnumSet.noneOf(DeploymentProjectType.class), null, counters,
                ManagedHelperProtocol.VERSION - 1);
        DeploymentLinuxGateway gateway = (endpoint, credential, verifier) -> session;

        DeploymentResult result = new ReviewedDeploymentService().deploy(request(runtimes().getFirst()), application(), gateway,
                new SshEndpoint("server-one", "example.test", 22, "deployer"),
                new SshCredential.Password("password".toCharArray()),
                (endpoint, fingerprint) -> HostKeyDecision.ACCEPT_EXISTING);

        assertEquals(DeploymentStatus.PRECONDITION_REJECTED, result.status());
        assertEquals(0, counters.uploads.get());
        assertEquals(0, counters.cleanups.get());
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals("helper-protocol") && !event.succeeded()));
    }

    private DeploymentRemoteSession fakeSession(EnumSet<DeploymentProjectType> built) {
        return fakeSession(built, null, new Counters());
    }

    private DeploymentRemoteSession fakeSession(EnumSet<DeploymentProjectType> built, String interruptedMethod,
                                                Counters counters) {
        return fakeSession(built, interruptedMethod, counters, ManagedHelperProtocol.VERSION);
    }

    private DeploymentRemoteSession fakeSession(EnumSet<DeploymentProjectType> built, String interruptedMethod,
                                                 Counters counters, int helperProtocolVersion) {
        return (DeploymentRemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DeploymentRemoteSession.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals(interruptedMethod)) {
                        throw LinuxOperationException.localized("linux.error.commandFailed", "fixture interruption");
                    }
                    return switch (method.getName()) {
                    case "collectCapabilities" -> new ServerCapabilityFacts("Ubuntu 24.04", "x86_64", true, true, true,
                            true, true, true, true, true, helperProtocolVersion, 10L * 1024 * 1024 * 1024, "fixture");
                    case "collectDeploymentCapabilities" -> new LinuxCapabilityFacts(LinuxDistroType.UBUNTU, "24.04",
                            "x86_64", "apt", "amd64", true, true, true, true,
                            java.util.Set.of(21), java.util.Set.of(22), true, true,
                            java.util.Set.of("3.12"), true, Map.of(
                            DeploymentProjectType.GO_SERVICE, java.util.Set.of("1.24"),
                            DeploymentProjectType.RUST_SERVICE, java.util.Set.of("1.89.0"),
                            DeploymentProjectType.DOTNET_SERVICE, java.util.Set.of("8.0.408"),
                            DeploymentProjectType.KOTLIN_SERVICE, java.util.Set.of("21"),
                            DeploymentProjectType.PHP_SERVICE, java.util.Set.of("8.3"),
                            DeploymentProjectType.RUBY_SERVICE, java.util.Set.of("3.3.5")),
                            Map.of(EcosystemToolType.JAVAC, java.util.Set.of("21.0.8"),
                                    EcosystemToolType.JAR, java.util.Set.of("21.0.8"),
                                    EcosystemToolType.NPM, java.util.Set.of("10.9.2"),
                                    EcosystemToolType.PIP, java.util.Set.of("24.0"),
                                    EcosystemToolType.COMPOSER, java.util.Set.of("2.8.10"),
                                    EcosystemToolType.BUNDLER, java.util.Set.of("2.6.9"),
                                    EcosystemToolType.CMAKE, java.util.Set.of("3.28.3"),
                                    EcosystemToolType.NINJA, java.util.Set.of("1.11.1"),
                                    EcosystemToolType.C_COMPILER, java.util.Set.of("13.3.0")),
                            true, true, CpuMicroarchitectureLevel.X86_64_V3, java.util.Set.of("sse4_2"),
                            new LinuxSecurityPosture(LinuxSecurityModuleType.APPARMOR, LinuxSecurityState.ENABLED,
                                    LinuxFirewallKind.UFW, LinuxFirewallState.ACTIVE), "fixture");
                    case "uploadSource" -> {
                        counters.uploads.incrementAndGet();
                        SourceArchiveDescriptor archive = (SourceArchiveDescriptor) arguments[0];
                        RemoteWorkspace workspace = (RemoteWorkspace) arguments[1];
                        yield new SourceUploadResult(workspace.candidateRoot() + "/mutable/source.tar.gz", archive.byteCount(),
                                archive.contentSha256(), "fixture upload");
                    }
                    case "buildDeployment" -> {
                        DeploymentProjectFacts facts = (DeploymentProjectFacts) arguments[0];
                        built.add(facts.projectType());
                        yield DeploymentBuildResult.succeeded(SHA, "fixture build");
                    }
                    case "stageDeploymentInputs" -> new DeploymentInputManifest(SHA, List.of());
                    case "snapshotDeployment" -> ReleaseSnapshot.firstDeployment("fixture snapshot");
                    case "cleanupCandidate" -> {
                        counters.cleanups.incrementAndGet();
                        yield new RemoteStepResult(true, false, "fixture cleanup");
                    }
                    case "rollbackDeployment" -> {
                        counters.rollbacks.incrementAndGet();
                        yield new RemoteStepResult(true, false, "fixture rollback");
                    }
                    case "publishDeployment", "retainRecentSuccessfulReleases" -> new RemoteStepResult(true, false, "fixture step");
                    case "checkDeploymentHealth" -> new HealthCheckResult(true, "fixture health");
                    case "observeDeployment", "executeDeploymentLifecycle" -> new LifecycleObservation(application(), RuntimeState.RUNNING,
                            AutostartState.DISABLED, true, Instant.now(), "fixture observation");
                    case "close" -> null;
                    case "toString" -> "fixture session";
                    default -> throw new AssertionError("unexpected remote capability: " + method.getName());
                    };
                });
    }

    private ReviewedDeploymentRequest request(DeploymentRuntimeSpecification runtime) {
        List<AnalysisEvidence> evidence = List.of(new AnalysisEvidence(LocalizedMessage.of("test.evidence"), "fixture",
                LocalizedMessage.of("test.detected"), EvidenceConfidenceLevel.HIGH));
        DeploymentProjectFacts facts = runtime.projectType() == DeploymentProjectType.CMAKE_SERVICE
                ? new DeploymentProjectFacts(temporaryDirectory, "demo", runtime.projectType(), tool(runtime),
                new ProjectLanguageFacts(java.util.Set.of(), java.util.Set.of(SourceLanguageType.C), Map.of(), List.of()),
                evidence, List.of(), List.of())
                : new DeploymentProjectFacts(temporaryDirectory, "demo", runtime.projectType(), tool(runtime),
                evidence, List.of(), List.of());
        ConfigurationSnapshot configuration = ConfigurationSnapshot.create("demo", 1, "v1", Instant.parse("2026-08-12T00:00:00Z"),
                List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME, new ConfigurationValue.Number(8080))));
        Optional<UserAccessUrl> url = runtime.healthCheck() instanceof HealthCheck.Http
                ? Optional.of(new UserAccessUrl(URI.create("https://example.test/"))) : Optional.empty();
        return new ReviewedDeploymentRequest(application().server(), facts, new SourceRevision(SHA, Optional.empty(), Map.of()),
                new SourceArchiveDescriptor(temporaryDirectory.resolve(runtime.projectType().name() + ".tar.gz"), SHA, 100, 100),
                configuration, List.of(), runtime, url, BuildLimitConfiguration.defaultNonRoot(),
                new DeploymentApproval("demo", SHA, "server-one", false, Instant.now()), true,
                facts.support().level() == gold.debug.windowstolinux.shared.model.project.DeploymentSupportLevel.EXPERIMENTAL_ADAPTER);
    }

    private static DeploymentBuildToolType tool(DeploymentRuntimeSpecification runtime) {
        return switch (runtime.projectType()) {
            case SPRING_BOOT -> DeploymentBuildToolType.GRADLE_WRAPPER;
            case JAVA_JAR -> DeploymentBuildToolType.JAVA;
            case JAVA_SOURCE -> DeploymentBuildToolType.JDK;
            case NODE_SERVICE -> DeploymentBuildToolType.NPM;
            case PYTHON_SERVICE -> DeploymentBuildToolType.PIP_LOCKED;
            case STATIC_SITE -> DeploymentBuildToolType.STATIC_SITE_BUILD;
            case DOCKERFILE_CONTAINER -> DeploymentBuildToolType.CONTAINER_BUILD;
            case GO_SERVICE -> DeploymentBuildToolType.GO_MODULE;
            case RUST_SERVICE -> DeploymentBuildToolType.CARGO_LOCKED;
            case DOTNET_SERVICE -> DeploymentBuildToolType.DOTNET_LOCKED;
            case KOTLIN_SERVICE -> DeploymentBuildToolType.GRADLE_KOTLIN_WRAPPER;
            case PHP_SERVICE -> DeploymentBuildToolType.COMPOSER_LOCKED;
            case RUBY_SERVICE -> DeploymentBuildToolType.BUNDLER_LOCKED;
            case CMAKE_SERVICE -> DeploymentBuildToolType.CMAKE;
            case RECOGNITION_PREVIEW -> throw new AssertionError("recognition preview has no deployment runtime");
        };
    }

    private static List<DeploymentRuntimeSpecification> runtimes() {
        return List.of(
                new DeploymentRuntimeSpecification.SpringBoot(new HealthCheck.Tcp(8080, 5, 1)),
                new DeploymentRuntimeSpecification.JavaJar("app.jar", "demo.Main", "21", List.of("-Xmx256m"), List.of(),
                        new HealthCheck.Tcp(8080, 5, 1)),
                new DeploymentRuntimeSpecification.JavaSource("src", "demo.Main", "21", List.of("-Xmx256m"), List.of(),
                        new HealthCheck.Tcp(8080, 5, 1)),
                new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(8080, 5, 1)),
                new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", new HealthCheck.Tcp(8080, 5, 1)),
                new DeploymentRuntimeSpecification.StaticSite("public", new HealthCheck.Http(URI.create("http://127.0.0.1:8080/"), 200, 5)),
                new DeploymentRuntimeSpecification.Container(DeploymentRuntimeSpecification.ContainerEngineType.PODMAN,
                        Map.of(8080, 8080), List.of(), new HealthCheck.Tcp(8080, 5, 1)),
                new DeploymentRuntimeSpecification.GoService("1.24", "w2l-app", "main.go", new HealthCheck.Tcp(8080, 5, 1)),
                new DeploymentRuntimeSpecification.RustService("1.89.0", "demo", "src/main.rs", new HealthCheck.Tcp(8080, 5, 1)),
                new DeploymentRuntimeSpecification.DotNetService("8.0.408", "Demo", "Demo.dll", new HealthCheck.Tcp(8080, 5, 1)),
                new DeploymentRuntimeSpecification.KotlinService("2.0.21", "demo", "demo.MainKt", new HealthCheck.Tcp(8080, 5, 1)),
                new DeploymentRuntimeSpecification.PhpService("8.3", "public", "public/index.php", 8080, new HealthCheck.Tcp(8080, 5, 1)),
                new DeploymentRuntimeSpecification.RubyService("3.3.5", "bundle", "config.ru", 8080, new HealthCheck.Tcp(8080, 5, 1)),
                new DeploymentRuntimeSpecification.CmakeService("w2l-release", "demo", "demo",
                        new HealthCheck.Tcp(8080, 5, 1))
        );
    }

    private static ManagedApplication application() {
        return ManagedApplication.forManaged("demo",
                new ServerIdentity("server-one", "example.test", 22, "SHA256:fixture"), SHA);
    }

    private static final class Counters {
        private final AtomicInteger connections = new AtomicInteger();
        private final AtomicInteger cleanups = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();
        private final AtomicInteger uploads = new AtomicInteger();
    }
}
