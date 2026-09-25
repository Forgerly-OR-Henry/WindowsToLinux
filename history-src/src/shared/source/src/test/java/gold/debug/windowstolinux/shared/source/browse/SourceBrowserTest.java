package gold.debug.windowstolinux.shared.source.browse;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceBrowserTest {
    @TempDir
    Path root;
    @Test
    void paginatesAndExcludesSecretsWithoutMutatingSource() throws Exception {
        Files.writeString(root.resolve("build.zig"),
                "hello\npassword=never-send\n-----BEGIN PRIVATE KEY-----\nsecret-body-123\n-----END PRIVATE KEY-----\ntail\n");
        Files.writeString(root.resolve(".env"), "TOKEN=excluded\n");
        var browser = new SourceBrowser(root);
        var digest = browser.revision();
        assertEquals(1, browser.list("", 0, 100).size());
        String text = String.join("\n", browser.read("build.zig", 0, 100));
        assertTrue(text.contains("hello"));
        assertFalse(text.contains("never-send"));
        assertFalse(text.contains("secret-body"));
        assertEquals(1, browser.search("tail", 0, 100).size());
        assertEquals(digest, browser.revision());
        browser.verify();
        assertThrows(Exception.class, () -> browser.read(".env", 0, 100));
        assertThrows(SecurityException.class, () -> browser.read("../outside", 0, 100));
        Files.writeString(root.resolve("build.zig"), "changed");
        assertThrows(SecurityException.class, browser::verify);
    }
}
