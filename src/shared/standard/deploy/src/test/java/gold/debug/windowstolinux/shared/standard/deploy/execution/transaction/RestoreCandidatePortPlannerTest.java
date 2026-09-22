package gold.debug.windowstolinux.shared.standard.deploy.execution.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gold.debug.windowstolinux.shared.deploy.contract.spi.CandidatePortMode;
import gold.debug.windowstolinux.shared.deploy.contract.spi.RestoreDeploymentComponent;
import gold.debug.windowstolinux.shared.deploy.contract.spi.RestoreDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.execution.transaction.RestoreCandidatePortPlanner;
import gold.debug.windowstolinux.shared.model.deployment.ReleaseSetDigest;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.Test;

class RestoreCandidatePortPlannerTest {
    private static final String ARCHIVE = "a".repeat(64);

    private static final String OWNER = "b".repeat(64);

    @Test
    void allocatesUniqueDynamicPortsOnlyWhenEveryRuntimeHasTypedOverride() {
        var container = component("api",
                new DeploymentRuntimeSpecification.Container(DeploymentRuntimeSpecification.ContainerEngineType.PODMAN,
                        Map.of(8080, 8080, 8443, 8443), List.of(), http(8080)));
        var site = component("web", new DeploymentRuntimeSpecification.StaticSite("public", http(8081)));
        RestoreDeploymentRequest request = request(List.of(container, site), "web", http(8081));

        var plan = new RestoreCandidatePortPlanner().plan(request, Set.of(49152, 49154));

        assertEquals(CandidatePortMode.PARALLEL_LOOPBACK, plan.mode());
        assertEquals(List.of(49153, 49155),
                plan.components().get("api").stream().map(binding -> binding.candidatePort()).toList());
        assertEquals(49156, plan.components().get("web").getFirst().candidatePort());
        assertTrue(plan.components().values().stream().flatMap(List::stream)
                .allMatch(binding -> binding.candidatePort() >= 49152));
        assertTrue(plan.components().values().stream().flatMap(List::stream)
                .allMatch(binding -> binding.candidatePort() <= 65535));
        assertTrue(plan.components().values().stream().flatMap(List::stream)
                .allMatch(binding -> binding.candidatePort() != binding.officialPort()));
    }

    @Test
    void makesTheWholeApplicationShortStopWhenOneRuntimeCannotOverrideItsBinding() {
        var site = component("web", new DeploymentRuntimeSpecification.StaticSite("public", http(8081)));
        var node = component("api", new DeploymentRuntimeSpecification.NodeService(20, http(8082)));

        var plan = new RestoreCandidatePortPlanner().plan(request(List.of(site, node), "api", http(8082)), Set.of());

        assertEquals(CandidatePortMode.SHORT_STOP, plan.mode());
        assertEquals(List.of(), plan.components().get("web"));
        assertEquals(List.of(), plan.components().get("api"));
    }

    @Test
    void doesNotUseAHealthPortAsAnOverrideForAnOpaqueRuntime() {
        var spring = component("api", new DeploymentRuntimeSpecification.SpringBoot(http(8080)));
        var plan = new RestoreCandidatePortPlanner().plan(request(List.of(spring), "api", http(8080)), Set.of());
        assertEquals(CandidatePortMode.SHORT_STOP, plan.mode());
    }

    @Test
    void failsWhenTheReviewedDynamicRangeIsExhausted() {
        var site = component("web", new DeploymentRuntimeSpecification.StaticSite("public", http(8081)));
        Set<Integer> unavailable = java.util.stream.IntStream.rangeClosed(49152, 65535).boxed()
                .collect(java.util.stream.Collectors.toSet());
        assertThrows(IllegalStateException.class,
                () -> new RestoreCandidatePortPlanner().plan(request(List.of(site), "web", http(8081)), unavailable));
    }

    @Test
    void phpAndRubyUseTheirTypedServicePorts() {
        var php = component("php",
                new DeploymentRuntimeSpecification.PhpService("8.3", "public", "public/index.php", 9001, http(9001)));
        var ruby = component("ruby",
                new DeploymentRuntimeSpecification.RubyService("3.3", "bundle", "config.ru", 9002, http(9002)));
        var plan = new RestoreCandidatePortPlanner().plan(request(List.of(php, ruby), "ruby", http(9002)), Set.of());
        assertEquals(CandidatePortMode.PARALLEL_LOOPBACK, plan.mode());
        assertEquals(9001, plan.components().get("php").getFirst().officialPort());
        assertEquals(9002, plan.components().get("ruby").getFirst().officialPort());
        assertNotEquals(plan.components().get("php").getFirst().candidatePort(),
                plan.components().get("ruby").getFirst().candidatePort());
    }

    private static RestoreDeploymentComponent component(String id, DeploymentRuntimeSpecification runtime) {
        return new RestoreDeploymentComponent(id, "managed-" + id, OWNER, digest(id), List.of(),
                "releases/" + id + ".pax", "config/" + id + ".bin", "runtime/" + id + ".bin", List.of(), runtime);
    }

    private static RestoreDeploymentRequest request(List<RestoreDeploymentComponent> components, String healthOwner,
            HealthCheck health) {
        String releaseSet = ReleaseSetDigest.sha256(components.stream().map(
                component -> new ReleaseSetDigest.ComponentRelease(component.componentId(), component.releaseSha256()))
                .toList());
        return new RestoreDeploymentRequest("demo", "server", "demo-" + ARCHIVE.substring(0, 16), ARCHIVE, releaseSet,
                List.of(), "/var/lib/windowstolinux/work/demo-" + ARCHIVE.substring(0, 16) + "/mutable/restore",
                ARCHIVE.substring(0, 32), components, healthOwner, health);
    }

    private static HealthCheck.Http http(int port) {
        return new HealthCheck.Http(URI.create("http://127.0.0.1:" + port + "/health"), 200, 10);
    }

    private static String digest(String value) {
        try {
            var sha = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(sha.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
