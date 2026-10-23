package gold.debug.windowstolinux.mod.deployment.linux;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentProtectedMetadataTest {
    @ParameterizedTest
    @ValueSource(strings = {"valid", "directory-writable", "marker-writable",
        "directory-unavailable", "marker-unavailable"})
    void protectedMetadataRejectsWritableOrUnverifiablePathsBeforeEffects(String scenario, @TempDir Path directory)
        throws Exception {
        Path bash = Path.of(System.getProperty("managed.test.bash", "E:/Program/Git/bin/bash.exe"));
        assertTrue(Files.isRegularFile(bash), "A real Bash binary is required");
        String foundation;
        try (var source = getClass().getResourceAsStream(
            "/gold/debug/windowstolinux/mod/deployment/linux/helper/00-protocol-foundation.sh")) {
            assertNotNull(source);
            foundation = new String(source.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        Files.createDirectory(directory.resolve("owned"));
        Files.writeString(directory.resolve("owned/marker"), "original-manifest");
        // Windows ownership and POSIX write permissions are emulated; path checks and Bash are real.
        String body = foundation + """
            fixture="$PWD"
            stat() { builtin printf '0:0\n'; }
            find() {
                [ "$#" -eq 8 ] && [ "$1" = -P ] && [ "$3" = -maxdepth ] && [ "$4" = 0 ] \
                    && [ "$5" = -perm ] && [ "$6" = /022 ] && [ "$7" = -print ] && [ "$8" = -quit ] || exit 97
                case "$scenario:$2" in
                    directory-writable:"$fixture/owned"|marker-writable:"$fixture/owned/marker") builtin printf '%s\n' "$2" ;;
                    directory-unavailable:"$fixture/owned"|marker-unavailable:"$fixture/owned/marker") return 1 ;;
                esac
            }
            assert_root_owned_directory "$fixture/owned"
            assert_root_owned_regular "$fixture/owned/marker"
            builtin printf 'effect\n' > "$fixture/effects"
            """;
        Path script = directory.resolve("guard.sh");
        Files.writeString(script, body);
        Path output = directory.resolve("output");
        var builder = new ProcessBuilder(bash.toString(), "--noprofile", "--norc", script.toString())
            .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("scenario", scenario);
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Owned Bash validation timed out");
            assertEquals(scenario.equals("valid") ? 0 : 64, process.exitValue(), Files.readString(output));
            assertEquals(scenario.equals("valid"), Files.exists(directory.resolve("effects")));
            assertEquals("original-manifest", Files.readString(directory.resolve("owned/marker")));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }
}
