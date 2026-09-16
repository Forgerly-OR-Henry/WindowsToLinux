package gold.debug.windowstolinux.shared.source.contract.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SourceSecretBoundaryTest {
    @TempDir Path root;

    @Test void excludesNestedAndCaseVariantCredentialFiles() throws Exception {
        Files.createDirectories(root.resolve("nested"));
        for (String name : List.of(".env.local", ".ENV.PRODUCTION", ".npmrc", ".pypirc"))
            Files.writeString(root.resolve("nested").resolve(name), "test-secret");
        Files.writeString(root.resolve("index.js"), "console.log('safe');");
        var manifest = new SourceBoundaryValidator().collect(root);
        assertEquals(1, manifest.entries().size());
        assertEquals(4, manifest.excludedEntries().size());
    }
}
