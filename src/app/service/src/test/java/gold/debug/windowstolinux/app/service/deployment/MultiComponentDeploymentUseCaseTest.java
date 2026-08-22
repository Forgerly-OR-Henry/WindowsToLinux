package gold.debug.windowstolinux.app.service.deployment;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.ManagedApplicationGraph;
import gold.debug.windowstolinux.app.db.entity.SuccessfulManagedDeployment;
import gold.debug.windowstolinux.app.service.DesktopApplicationFacade;
import gold.debug.windowstolinux.app.service.deployment.multi.MultiComponentReviewInput;
import gold.debug.windowstolinux.app.service.deployment.multi.ReviewedComponentApplication;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.analyze.component.ComponentAnalysisRequest;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.component.ComponentIsolationSpecification;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the desktop whole-application review boundary without opening SSH. / 测试不打开 SSH 的桌面整应用审阅边界。 */
class MultiComponentDeploymentUseCaseTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void bindsEveryComponentToIndependentSourceConfigurationRuntimeAndManagedIdentity() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("shop"));
        node(Files.createDirectories(root.resolve("api")), "api");
        node(Files.createDirectories(root.resolve("web")), "web");
        AtomicInteger connections = new AtomicInteger();
        DeploymentLinuxGateway gateway = (endpoint, credential, verifier) -> {
            connections.incrementAndGet();
            throw new AssertionError("review validation must not open SSH");
        };
        try (DesktopPersistence persistence = DesktopPersistence.open(temporaryDirectory.resolve("data"))) {
            DesktopApplicationFacade service = new DesktopApplicationFacade(
                    persistence, temporaryDirectory.resolve("work"), gateway);
            var prepared = service.prepareReviewedMultiComponentSource(root, "shop", List.of(
                    component("api", "api", 18081, Set.of()),
                    component("web", "web", 18082, Set.of("api"))));
            ServerIdentity server = new ServerIdentity("server-one", "192.0.2.10", 22,
                    "SHA256:AAAAAAAAAAAA");
            var review = service.createReviewedMultiComponentApplication(prepared, server, List.of(
                    input("api", "shop-api", 18081, List.of()),
                    input("web", "shop-web", 18082, List.of(new SecretReference("web-token", 1)))),
                    new ApplicationHealthGate("web", new HealthCheck.Tcp(18082, 20, 1)));

            assertEquals(List.of("api", "web"), review.plan().startOrder());
            assertEquals(List.of("shop-api", "shop-web"), review.components().stream()
                    .map(value -> value.application().id()).toList());
            assertEquals(2, review.components().stream().map(value -> value.request().archive().contentSha256())
                    .distinct().count());
            assertEquals("web", review.applicationHealth().componentId());

            List<SuccessfulManagedDeployment> successful = new java.util.ArrayList<>();
            List<ManagedApplicationGraph.Component> durableComponents = new java.util.ArrayList<>();
            for (int index = 0; index < review.components().size(); index++) {
                ReviewedComponentApplication componentReview = review.components().get(index);
                var runtime = new ManagedApplicationRuntimeConfiguration(
                        componentReview.request().runtime().healthCheck(), Optional.empty());
                successful.add(new SuccessfulManagedDeployment(componentReview.application(), runtime,
                        new CurrentRelease(componentReview.application().id(),
                                Character.toString((char) ('e' + index)).repeat(64), Instant.now()),
                        componentReview.request().configuration(), List.of()));
                durableComponents.add(new ManagedApplicationGraph.Component(componentReview.componentId(),
                        componentReview.application(), runtime,
                        review.plan().dependencies().get(componentReview.componentId()),
                        Optional.of(componentReview.request().runtime()),
                        Optional.of(componentReview.resourceBindings().fileBindings().stream()
                                .map(binding -> binding.dataPath()).toList()),
                        Optional.of(componentReview.resourceBindings())));
            }
            persistence.managedApplicationGraphs().recordSuccessfulApplication(
                    new ManagedApplicationGraph("shop", "web", durableComponents), successful);
            var storedGraph = persistence.managedApplicationGraphs().find("shop").orElseThrow();
            var restored = service.findManagedMultiComponentApplication("shop").orElseThrow();
            assertEquals(List.of("api", "web"), restored.plan().startOrder());
            assertEquals(List.of("shop-api", "shop-web"), restored.components().stream()
                    .map(value -> value.application().id()).toList());
            assertEquals(List.of("api-data", "web-data"), storedGraph.components().stream()
                    .flatMap(value -> value.reviewedDataPaths().orElseThrow().stream())
                    .map(ComponentDataPath::path).toList());

            assertThrows(gold.debug.windowstolinux.app.service.failure.ApplicationServiceException.class,
                    () -> service.deployReviewedMultiComponent(review,
                            new gold.debug.windowstolinux.shared.linux.connection.SshEndpoint(
                                    "server-one", "192.0.2.10", 22, "root"),
                            new gold.debug.windowstolinux.shared.linux.connection.SshCredential.Password(
                                    "unused".toCharArray()), (endpoint, fingerprint) ->
                                    gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision.ACCEPT_EXISTING));
            assertEquals(0, connections.get());

            char[] masterPassword = "must-clear".toCharArray();
            ServerProfile wrongProfile = new ServerProfile("server-two", "192.0.2.11", 22, "root",
                    "ssh/server-two/password", CredentialStorageMode.MASTER_PASSWORD);
            assertThrows(gold.debug.windowstolinux.app.service.failure.ApplicationServiceException.class,
                    () -> service.deployReviewedMultiComponentWithStoredPassword(review, wrongProfile,
                            CredentialStorageMode.MASTER_PASSWORD, masterPassword, ignored -> false));
            for (char value : masterPassword) assertEquals('\0', value);
            assertTrue(connections.get() == 0);
        }
    }

    private static ComponentAnalysisRequest component(String id, String root, int port, Set<String> dependencies) {
        return new ComponentAnalysisRequest(id, root, DeploymentProjectType.NODE_SERVICE,
                Optional.of(new DeploymentRuntimeSpecification.NodeService(22,
                        new HealthCheck.Tcp(port, 20, 1))),
                List.of(root + "/dist"), Set.of(port), List.of("PORT"), List.of(),
                List.of(new ComponentDataPath(root + "-data", ComponentDataPath.AccessMode.READ_WRITE,
                        root + "-v1", true)), dependencies,
                true, ComponentIsolationSpecification.managed());
    }

    private static MultiComponentReviewInput input(String componentId, String applicationId, int port,
                                                   List<SecretReference> secrets) {
        return new MultiComponentReviewInput(componentId,
                ConfigurationSnapshot.create(applicationId, 1, "v1", Instant.parse("2026-08-13T00:00:00Z"),
                        List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME,
                                new ConfigurationValue.Number(port)))),
                secrets, Optional.empty(), BuildLimitConfiguration.defaultNonRoot(), false, false);
    }

    private static void node(Path directory, String name) throws Exception {
        Files.writeString(directory.resolve("package.json"), """
                {"name":"%s","engines":{"node":"22"},"scripts":{"build":"build","start":"start"}}
                """.formatted(name));
        Files.writeString(directory.resolve("package-lock.json"), """
                {"name":"%s","version":"1.0.0","lockfileVersion":3,"requires":true,
                 "packages":{"":{"name":"%s","version":"1.0.0"}}}
                """.formatted(name, name));
    }
}
