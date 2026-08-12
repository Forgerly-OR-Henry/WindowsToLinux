package gold.debug.windowstolinux.shared.git.snapshot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitCommandRunnerTest {
    @Test
    void usesOpenSslForNonInteractiveHttpsOnWindows() {
        assertEquals(List.of("git", "-c", "http.sslBackend=openssl", "fetch", "origin"),
                GitCommandRunner.commandForPlatform(List.of("git", "fetch", "origin"), "Windows 11"));
    }

    @Test
    void leavesThePlatformGitDefaultUnchangedElsewhere() {
        assertEquals(List.of("git", "fetch", "origin"),
                GitCommandRunner.commandForPlatform(List.of("git", "fetch", "origin"), "Linux"));
    }
}
