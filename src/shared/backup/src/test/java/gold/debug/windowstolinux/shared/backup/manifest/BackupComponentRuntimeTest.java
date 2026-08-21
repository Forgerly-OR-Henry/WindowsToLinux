package gold.debug.windowstolinux.shared.backup.manifest;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackupComponentRuntimeTest {
    private static final HealthCheck TCP = new HealthCheck.Tcp(8080, 30, 5);
    private static final HealthCheck.Http HTTP = new HealthCheck.Http(
            URI.create("http://127.0.0.1:8080/health"), 200, 30);

    @Test
    void roundTripsEveryReviewedRuntimeWithoutTextInterpretation() throws Exception {
        List<DeploymentRuntimeSpecification> runtimes = List.of(
                new DeploymentRuntimeSpecification.SpringBoot(TCP),
                new DeploymentRuntimeSpecification.JavaJar("app.jar", "demo.Main", "21",
                        List.of("-Xmx512m"), List.of("--server.port=8080"), TCP),
                new DeploymentRuntimeSpecification.JavaSource("src", "demo.Main", "21",
                        List.of("-Xmx512m"), List.of("--server.port=8080"), TCP),
                new DeploymentRuntimeSpecification.NodeService(22, TCP),
                new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", TCP),
                new DeploymentRuntimeSpecification.StaticSite("dist", OptionalInt.of(22), HTTP),
                new DeploymentRuntimeSpecification.Container(
                        DeploymentRuntimeSpecification.ContainerEngineType.PODMAN,
                        Map.of(8080, 8080),
                        List.of(new DeploymentRuntimeSpecification.ManagedVolume(
                                "windowstolinux-data", "/data", false)), TCP),
                new DeploymentRuntimeSpecification.GoService("1.24", "demo", "main.go", TCP),
                new DeploymentRuntimeSpecification.RustService("1.90.0", "demo", "src/main.rs", TCP),
                new DeploymentRuntimeSpecification.DotNetService("8.0", "demo", "demo.dll", TCP),
                new DeploymentRuntimeSpecification.KotlinService("2.1.0", "demo", "demo.MainKt", TCP),
                new DeploymentRuntimeSpecification.PhpService(
                        "8.4", "public", "public/index.php", 8080, TCP),
                new DeploymentRuntimeSpecification.RubyService(
                        "3.4.1", "bundle", "config.ru", 8080, TCP),
                new DeploymentRuntimeSpecification.CmakeService("release", "demo", "demo", TCP));
        BackupManifestCodec codec = new BackupManifestCodec();

        for (DeploymentRuntimeSpecification runtime : runtimes) {
            BackupComponentRuntime portable = BackupComponentRuntime.from(runtime);
            BackupManifest restored = codec.read(codec.write(manifest(portable)));
            BackupComponentRuntime decoded = restored.inventory().components().getFirst().runtime();

            assertEquals(runtime, portable.toSpecification());
            assertEquals(runtime, decoded.toSpecification());
        }
    }

    @Test
    void rejectsUnknownRuntimeFieldsAndKinds() throws Exception {
        BackupManifestCodec codec = new BackupManifestCodec();
        String json = new String(codec.write(manifest(
                new BackupComponentRuntime.SpringBoot(BackupHealthCheck.from(TCP)))), StandardCharsets.UTF_8);
        String unknownField = json.replace("\"kind\":\"spring-boot\"",
                "\"kind\":\"spring-boot\",\"command\":\"arbitrary\"");
        String unknownKind = json.replace("\"kind\":\"spring-boot\"", "\"kind\":\"shell\"");

        assertThrows(Exception.class, () -> codec.read(unknownField.getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class, () -> codec.read(unknownKind.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsOutOfOrderDependenciesAndMissingDefinitionMembers() {
        BackupHealthCheck health = BackupHealthCheck.from(TCP);
        BackupComponent first = component("first", List.of(), health);
        BackupComponent second = component("second", List.of("first"), health);

        assertThrows(IllegalArgumentException.class, () -> inventory(
                List.of(second, first), "first", health));

        BackupInventory inventory = inventory(List.of(first), "first", health);
        assertThrows(IllegalArgumentException.class, () -> BackupManifest.create(
                Instant.parse("2026-08-22T00:00:00Z"), "sample", inventory,
                List.of(new BackupMember("config/first.json", 0, "a".repeat(64),
                        BackupMemberKind.CONFIGURATION))));
    }

    private static BackupManifest manifest(BackupComponentRuntime runtime) {
        BackupHealthCheck health = BackupHealthCheck.from(runtime.healthCheck().toHealthCheck());
        BackupComponent component = new BackupComponent("sample", "sample", "b".repeat(64),
                "releases/sample.json", "config/sample.json", "runtime/sample.service", List.of(), runtime,
                "c".repeat(64), List.of());
        BackupInventory inventory = inventory(List.of(component), "sample", health);
        return BackupManifest.create(Instant.parse("2026-08-22T00:00:00Z"), "sample", inventory,
                members("sample"));
    }

    private static BackupInventory inventory(
            List<BackupComponent> components, String healthComponentId, BackupHealthCheck health) {
        List<String> ids = components.stream().map(BackupComponent::componentId).toList();
        return new BackupInventory(
                ids.stream().map(id -> "releases/" + id + ".json").toList(),
                ids.stream().map(id -> "config/" + id + ".json").toList(), List.of(), List.of(), List.of(),
                BackupDatabase.none(),
                new BackupIdentity("sample", "server-1", "/var/lib/windowstolinux/apps/sample",
                        BackupInventory.computeReleaseSetSha256(components)),
                ids.stream().map(id -> "runtime/" + id + ".service").toList(),
                components, healthComponentId, health,
                new BackupRuntime("ubuntu", "24.04", "systemd", "255", "x86_64", List.of("systemd")),
                List.of());
    }

    private static BackupComponent component(String id, List<String> dependencies, BackupHealthCheck health) {
        return new BackupComponent(id, id, "b".repeat(64), "releases/" + id + ".json",
                "config/" + id + ".json", "runtime/" + id + ".service", dependencies,
                new BackupComponentRuntime.SpringBoot(health), "c".repeat(64), List.of());
    }

    private static List<BackupMember> members(String id) {
        return List.of(
                new BackupMember("releases/" + id + ".json", 0, "a".repeat(64), BackupMemberKind.RELEASE),
                new BackupMember("config/" + id + ".json", 0, "a".repeat(64), BackupMemberKind.CONFIGURATION),
                new BackupMember("runtime/" + id + ".service", 0, "a".repeat(64), BackupMemberKind.RUNTIME));
    }
}
