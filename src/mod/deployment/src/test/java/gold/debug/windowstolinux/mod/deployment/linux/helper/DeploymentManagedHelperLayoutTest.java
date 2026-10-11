package gold.debug.windowstolinux.mod.deployment.linux.helper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayoutTemplate;

class DeploymentManagedHelperLayoutTest {
    @Test
    void explicitOriginalLayoutPreservesTheExistingEntryAndIntegrityGuard() {
        var layout = DeploymentRemoteLayout.original();
        assertEquals(DeploymentManagedHelperBundle.renderBuildEntry(), DeploymentManagedHelperBundle.renderBuildEntry(layout));
        assertEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256, DeploymentManagedHelperBundle.helperSha256(layout));
        String command = "'" + layout.helperPath() + "' probe";
        assertEquals(DeploymentManagedHelperBundle.verifiedCommand(command), DeploymentManagedHelperBundle.verifiedCommand(command, layout));
    }

    @Test
    void nonDefaultRootsReachShellPythonToolchainsAndBuildEntry() {
        var layout = custom("A");
        String helper = DeploymentManagedHelperBundle.renderScript(layout);
        String entry = DeploymentManagedHelperBundle.renderBuildEntry(layout);
        assertFalse(helper.contains("@wtl:"));
        for (String old : new String[]{"/usr/local/lib/windowstolinux", "/var/lib/windowstolinux",
            "/opt/windowstolinux/apps", "/etc/opt/windowstolinux/apps", "/var/opt/windowstolinux/apps"})
            assertFalse(helper.contains(old), old);
        assertTrue(helper.contains("helper_path=/srv/wtl.helper-A/managed-helper"));
        assertTrue(helper.contains("applications_root=/srv/wtl.apps-A"));
        assertTrue(helper.contains("configurations_root=/srv/wtl.config-A"));
        assertTrue(helper.contains("secrets_root=/srv/wtl.config-A"));
        assertTrue(helper.contains("data_root=/srv/wtl.data-A"));
        assertTrue(helper.contains("ROOT = pathlib.Path('/srv/wtl.helper-A/toolchains')"));
        assertTrue(DeploymentToolchainSources.metadata(layout).contains("ROOT = pathlib.Path('/srv/wtl.helper-A/toolchains')"));
        assertTrue(DeploymentToolchainSources.metadata().contains("ROOT = pathlib.Path('/usr/local/lib/windowstolinux/toolchains')"));
        assertTrue(helper.contains("directory = pathlib.Path('/srv/wtl.state-A/db')"));
        assertTrue(helper.contains("r\"/srv/wtl\\.state-A/work/"));
        assertTrue(entry.contains("^/srv/wtl\\.state-A/work/"));
        assertTrue(helper.contains("build_entry_sha256=" + DeploymentManagedHelperBundle.buildEntrySha256(layout)));
        assertNotEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256, DeploymentManagedHelperBundle.helperSha256(layout));
        String expression = DeploymentRemoteLayoutTemplate.render("@wtl:state-root-regex@/work/test", layout);
        assertTrue("/srv/wtl.state-A/work/test".matches(expression));
        assertFalse("/srv/wtlXstate-A/work/test".matches(expression));
    }

    @Test
    void integrityGuardRejectsAnotherLayoutAndKeepsTheCompleteInvocation() {
        var first = custom("A");
        var second = custom("B");
        String body = "'" + first.helperPath() + "' probe";
        String guarded = DeploymentManagedHelperBundle.verifiedCommand(body, first);
        assertEquals(body, DeploymentManagedHelperBundle.verifiedBody(guarded, first));
        assertTrue(guarded.contains(DeploymentManagedHelperBundle.helperSha256(first)));
        assertTrue(guarded.contains("in / /srv /srv/wtl.helper-A;"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentManagedHelperBundle.verifiedBody(guarded, second));
        assertThrows(IllegalArgumentException.class, () -> DeploymentManagedHelperBundle.verifiedCommand(body, second));
        assertThrows(IllegalArgumentException.class, () -> DeploymentManagedHelperBundle.verifiedCommand(guarded, first));
    }

    @Test
    void rejectsUnknownTemplateSlotsInsteadOfLeavingAnExecutablePlaceholder() {
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentRemoteLayoutTemplate.render("ROOT='@wtl:unknown-root@'", custom("A")));
    }

    @Test
    void renderedRegexGuardsMatchOnlyTheConfiguredLiteralPath() throws Exception {
        var layout = custom("A");
        String entryCheck = DeploymentManagedHelperBundle.renderBuildEntry(layout).lines()
            .filter(line -> line.startsWith("[[ \"$candidate\" =~ ")).findFirst().orElseThrow();
        String candidate = layout.workRoot() + "/task-board-0123456789abcdef";
        assertEquals(0, execute("candidate='" + candidate + "'\n" + entryCheck + "\n"));
        assertEquals(64, execute("candidate='" + candidate.replace("wtl.state", "wtlXstate") + "'\n" + entryCheck + "\n"));
        String toolchainCheck = DeploymentManagedHelperBundle.renderScript(layout).lines()
            .filter(line -> line.contains("[[ \"$directory\" =~ ^") && line.contains("/toolchains/versions/"))
            .findFirst().orElseThrow();
        String toolchain = layout.toolchains() + "/versions/python-" + "a".repeat(64);
        assertEquals(0, execute("reject() { exit 64; }\ndirectory='" + toolchain + "'\n" + toolchainCheck + "\n"));
        assertEquals(64, execute("reject() { exit 64; }\ndirectory='" + toolchain.replace("wtl.helper", "wtlXhelper")
            + "'\n" + toolchainCheck + "\n"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void renderedShellResourcesRemainSyntacticallyValid(boolean customized) throws Exception {
        var layout = customized ? custom("A") : DeploymentRemoteLayout.original();
        syntax(DeploymentManagedHelperBundle.renderScript(layout));
        syntax(DeploymentManagedHelperBundle.renderBuildEntry(layout));
        syntax(DeploymentManagedHelperBundle.verifiedCommand("'" + layout.helperPath() + "' probe", layout));
    }

    private static void syntax(String script) throws Exception {
        assertEquals(0, run(script, "-n"));
    }

    private static int execute(String script) throws Exception {
        return run(script, "-e");
    }

    private static int run(String script, String mode) throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        var process = new ProcessBuilder(bash, "--noprofile", "--norc", mode, "-s").redirectErrorStream(true).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(script.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Shell syntax check timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(output.isEmpty(), output);
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }

    private static DeploymentRemoteLayout custom(String suffix) {
        return new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths("/srv/wtl.helper-" + suffix,
            "/srv/wtl.apps-" + suffix, "/srv/wtl.state-" + suffix,
            "/srv/wtl.config-" + suffix, "/srv/wtl.data-" + suffix));
    }
}
