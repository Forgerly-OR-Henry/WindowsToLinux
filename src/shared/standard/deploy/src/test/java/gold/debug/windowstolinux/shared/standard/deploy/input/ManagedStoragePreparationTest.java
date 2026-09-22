package gold.debug.windowstolinux.shared.standard.deploy.input;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.shared.config.resource.*;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedStoragePreparationTest {
    @TempDir
    Path root;
    private ConfigurationSnapshot configuration() {
        return ConfigurationSnapshot.create("demo", 1, "v1", Instant.EPOCH,
                List.of(new gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry("PORT",
                        gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope.RUNTIME,
                        new gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue.Number(
                                8080))));
    }

    private DeploymentRuntimeSpecification runtime() {
        return new DeploymentRuntimeSpecification.SpringBoot(new HealthCheck.Tcp(8080, 10, 2));
    }

    private ManagedStoragePreparation.Prepared prepare(String text) throws Exception {
        Files.writeString(root.resolve("windowstolinux-storage.properties"), text);
        return ManagedStoragePreparation.prepare(root, "demo", configuration(), runtime(), List.of());
    }

    @Test
    void passesDefaultStorageToAnExplicitApplicationEnvironment() throws Exception {
        var result = prepare("storage=uploads\nstorage.uploads.environment=UPLOADS_DIRECTORY\n");
        assertEquals("/var/opt/windowstolinux/apps/demo/files/uploads", result.files().getFirst().physicalPath("demo"));
        assertTrue(result.configuration().entries().stream().anyMatch(entry -> entry.key().equals("UPLOADS_DIRECTORY")
                && entry.value().toString().contains("/var/opt/windowstolinux/apps/demo/files/uploads")));
    }

    @Test
    void relativePathsRemainStableAndConfigurationRevisionsChangeOnlyWithContent() throws Exception {
        var data = prepare("storage=uploads\nstorage.uploads.path=uploads\n").files().getFirst();
        assertEquals("uploads", data.dataPath().path());
        assertEquals("/opt/windowstolinux/apps/demo/persistent/files/uploads", data.physicalPath("demo"));
        Files.writeString(root.resolve("settings.json"), "{}");
        String manifest = "storage=settings\nstorage.settings.kind=CONFIGURATION\nstorage.settings.path=config/settings.json\nstorage.settings.seed=settings.json\n";
        var first = prepare(manifest).files().getFirst();
        assertEquals(first, prepare(manifest).files().getFirst());
        Files.writeString(root.resolve("settings.json"), "{\"port\":8080}");
        var second = prepare(manifest).files().getFirst();
        assertNotEquals(first.physicalPath("demo"), second.physicalPath("demo"));
        assertTrue(second.physicalPath("demo").endsWith("/value"));
    }

    @Test
    void unknownInputsAndExplicitOutOfBoundsPathsNeverFallBack() throws Exception {
        for (String text : List.of("storage=a\n", "storage=a\nstorage.a.location=UNRESOLVED\n",
                "storage=a\nstorage.a.path=/opt/windowstolinux/apps/demo2/files\n",
                "storage=a\nstorage.a.path=${DATA_DIR}\n",
                "storage=a\nstorage.a.location=DEFAULT\nstorage.a.path=/tmp/files\n"))
            assertThrows(IllegalArgumentException.class, () -> prepare(text), text);
    }

    @Test
    void applicationConfigurationCannotSilentlyOverrideStorageHandoff() throws Exception {
        var initial = prepare("storage=a\nstorage.a.environment=DATA_DIR\n");
        Files.writeString(root.resolve("windowstolinux-storage.properties"),
                "storage=a\nstorage.a.path=other\nstorage.a.environment=DATA_DIR\n");
        assertThrows(IllegalArgumentException.class,
                () -> ManagedStoragePreparation.prepare(root, "demo", initial.configuration(), runtime(), List.of()));
    }
}
