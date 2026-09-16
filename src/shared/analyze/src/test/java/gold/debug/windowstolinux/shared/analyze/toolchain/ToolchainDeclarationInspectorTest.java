package gold.debug.windowstolinux.shared.analyze.toolchain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolchainDeclarationInspectorTest {
    @TempDir Path root;
    @Test void retainsOldFuturePreviewAndUnresolvedDeclarationsWithoutACatalog() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<java.version>1.8</java.version>");
        Files.writeString(root.resolve("package.json"), "{\"engines\":{\"node\":\"99.0.0-rc1\"}}");
        Files.writeString(root.resolve("pyproject.toml"), "requires-python = '>=3.7,<4'\n");
        Files.writeString(root.resolve("rust-toolchain.toml"), "channel = 'nightly-2099-01-01'\n");
        Files.writeString(root.resolve("go.mod"), "go 1.99.3\n");
        Files.writeString(root.resolve("CMakeLists.txt"), "target_compile_features(example PUBLIC cxx_std_99)\n");
        var requirements = new ToolchainDeclarationInspector().inspect(root);
        assertEquals(6, requirements.size());
        assertEquals("1.8", requirements.stream().filter(r -> r.ecosystem() == JAVA).findFirst().orElseThrow().declaration());
        assertTrue(requirements.stream().filter(r -> r.ecosystem() == NODE).findFirst().orElseThrow().version().orElseThrow().preview());
        assertTrue(requirements.stream().filter(r -> r.ecosystem() == PYTHON || r.ecosystem() == RUST).allMatch(r -> r.version().isEmpty()));
        assertTrue(requirements.stream().allMatch(r -> !r.source().isBlank()));
    }
}
