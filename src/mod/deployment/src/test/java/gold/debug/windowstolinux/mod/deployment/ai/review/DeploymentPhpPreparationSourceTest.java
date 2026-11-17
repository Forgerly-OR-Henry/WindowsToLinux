package gold.debug.windowstolinux.mod.deployment.ai.review;

import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentPhpPreparationSourceTest {
    @Test
    void selectedPhpPreparationRequiresTheActualWorkerAndPrivilegedFallbackGuards() throws Exception {
        String helper = DeploymentManagedHelperBundle.renderScript();
        Map<String, String> required = PreparationSourceRange.helper(helper, "PHP", true).stream()
            .filter(PreparationSourceRange::phpBuildRequired)
            .collect(Collectors.toMap(PreparationSourceRange::section, range -> helper.substring(range.offset(), range.next())));

        String worker = required.get("phpSourceWorker");
        assertTrue(worker.contains("require_isolated(network, mount)"));
        assertTrue(worker.contains("bootstrap_gnu_make(resource_limits[0])"));
        assertTrue(worker.contains("\"INSTALL_ROOT=\" + str(PHP_DESTDIR), \"DESTDIR=\" + str(PHP_DESTDIR)"));
        assertTrue(worker.contains("!extension_loaded($extension)"));
        assertTrue(required.get("phpSourceAdapter").contains("require_php_source_record(record, directory)"));
        assertTrue(required.get("phpLegacyReceiverGuard").contains("requires the isolated worker"));
        assertTrue(required.get("phpLegacyProbeGuard").contains("requires the isolated worker"));
        assertTrue(required.get("def source_unit(").contains("PrivateNetwork=yes"));
    }

    @Test
    void systemPhpPinsTheExecutableItProbedAndVerifiesBeforePublishingTheMarker(@TempDir Path temporary) throws Exception {
        Path fixture = temporary.resolve("php-system-discovery.py");
        try (var input = getClass().getResourceAsStream("php-system-discovery.py")) {
            assertNotNull(input);
            Files.copy(input, fixture);
        }
        Path helper = Files.writeString(temporary.resolve("helper.sh"), DeploymentManagedHelperBundle.renderScript());
        String configuredPython = System.getProperty("managed.test.python");
        var builder = new ProcessBuilder(configuredPython == null ? "python" : configuredPython, "-I", "-B", fixture.toString())
            .directory(temporary.toFile())
            .redirectInput(helper.toFile())
            .redirectErrorStream(true);
        for (String name : new String[] {"TEMP", "TMP", "TMPDIR"})
            builder.environment().put(name, temporary.toString());
        Process process;
        try {
            process = builder.start();
        } catch (IOException unavailable) {
            if (configuredPython != null)
                throw unavailable;
            Assumptions.assumeTrue(false, "Python fixture interpreter unavailable");
            return;
        }
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "PHP fixture timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }
}
