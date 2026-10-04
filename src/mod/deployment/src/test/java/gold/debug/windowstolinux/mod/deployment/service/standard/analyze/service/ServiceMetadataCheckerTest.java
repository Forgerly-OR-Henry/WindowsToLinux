package gold.debug.windowstolinux.mod.deployment.service.standard.analyze.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceMetadataCheckerTest {
    @TempDir
    Path root;

    @Test
    void runtimeEntryDeclarationDoesNotRequireReplacingTheBundlerLaunchCommand() throws Exception {
        Files.writeString(root.resolve("windowstolinux-application.properties"),
            "runtime.secondary=server.rb\ncommand.entrypoint=client.rb\n");
        assertEquals("server.rb", ServiceMetadataChecker.applicationEntrypoint(root, "config.ru"));
    }

    @Test
    void retainsExplicitCommandAndConventionalEntrypointDefaults() throws Exception {
        assertEquals("public/index.php", ServiceMetadataChecker.applicationEntrypoint(root, "public/index.php"));
        Files.writeString(root.resolve("windowstolinux-application.properties"), "command.entrypoint=router.php\n");
        assertEquals("router.php", ServiceMetadataChecker.applicationEntrypoint(root, "public/index.php"));
    }

    @Test
    void rejectsDeclaredRuntimePathsOutsideTheSource() throws Exception {
        Files.writeString(root.resolve("windowstolinux-application.properties"), "runtime.secondary=../server.rb\n");
        assertThrows(IllegalArgumentException.class,
            () -> ServiceMetadataChecker.applicationEntrypoint(root, "config.ru"));
    }
}
