package gold.debug.windowstolinux.shared.analyze.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ServiceMetadataInspectorTest {
    @TempDir Path root;

    @Test
    void runtimeEntryDeclarationDoesNotRequireReplacingTheBundlerLaunchCommand() throws Exception {
        Files.writeString(root.resolve("windowstolinux-application.properties"),
                "runtime.secondary=server.rb\ncommand.entrypoint=client.rb\n");
        assertEquals("server.rb", ServiceMetadataInspector.applicationEntrypoint(root, "config.ru"));
    }

    @Test
    void retainsExplicitCommandAndConventionalEntrypointDefaults() throws Exception {
        assertEquals("public/index.php", ServiceMetadataInspector.applicationEntrypoint(root, "public/index.php"));
        Files.writeString(root.resolve("windowstolinux-application.properties"), "command.entrypoint=router.php\n");
        assertEquals("router.php", ServiceMetadataInspector.applicationEntrypoint(root, "public/index.php"));
    }

    @Test
    void rejectsDeclaredRuntimePathsOutsideTheSource() throws Exception {
        Files.writeString(root.resolve("windowstolinux-application.properties"), "runtime.secondary=../server.rb\n");
        assertThrows(IllegalArgumentException.class, () -> ServiceMetadataInspector.applicationEntrypoint(root, "config.ru"));
    }
}
