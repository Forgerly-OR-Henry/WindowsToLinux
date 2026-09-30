package gold.debug.windowstolinux.mod.deployment.source.git;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

class DeploymentSourceSelectionMigrationTest {
    @Test
    void identifiesNetworkGitUrlsWithoutBroadeningPrivateRepositorySyntax() {
        for (String value : List.of("https://EXAMPLE.test/repo.git", "ssh://EXAMPLE.test/repo")) {
            var remote = DeploymentGitRemote.parse(value);
            assertEquals(URI.create(value), remote.location());
            assertEquals("example.test", remote.host().orElseThrow());
            assertNull(remote.location().getRawUserInfo());
            assertNull(remote.location().getRawQuery());
        }
        for (String value : List.of("", "git@example.test:repo.git", "https://user:secret@example.test/repo",
                "https://example.test/repo?token=secret", "http://example.test/repo",
                "https://example.test/repo\nhttps://example.test/other")) {
            assertThrows(IllegalArgumentException.class, () -> DeploymentGitRemote.parse(value), value);
        }
    }
}
