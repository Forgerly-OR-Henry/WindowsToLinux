package gold.debug.windowstolinux.app.service.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class SourceSelectionServiceTest {
    @TempDir Path directory;

    @Test void identifiesDirectoryAndUrlsWithoutBroadeningPrivateRepositorySyntax() throws Exception {
        assertEquals(directory, SourceSelectionService.identify(directory.toString()).directory().orElseThrow());
        assertEquals(directory, SourceSelectionService.identify(directory.toUri().toString()).directory().orElseThrow());
        assertEquals("https://example.test/repo.git", SourceSelectionService.identify("https://example.test/repo.git").gitAddress());
        assertEquals("ssh://example.test/repo", SourceSelectionService.identify("ssh://example.test/repo").gitAddress());
        for (String value : java.util.List.of("", directory.resolve("missing").toString(), "git@example.test:repo.git",
                "https://user:secret@example.test/repo", "https://example.test/repo?token=secret", "http://example.test/repo", "https://example.test/repo\nhttps://example.test/other"))
            assertThrows(IllegalArgumentException.class, () -> SourceSelectionService.identify(value));
        var file = Files.writeString(directory.resolve("file.txt"), "fixture");
        assertThrows(IllegalArgumentException.class, () -> SourceSelectionService.identify(file.toString()));
    }
}
