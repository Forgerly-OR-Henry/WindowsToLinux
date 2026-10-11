package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentAgentHelperInstallerScriptTest {
    private static final String OPERATION = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path temporary;

    @Test
    void defaultFilesInstallerRetainsItsOriginalBytes() throws Exception {
        assertEquals("0ac82ab0aa2b4b54c2271097fda264bd5d5d10876fbfffd1d37a49b21f3a2fed",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(DeploymentHelperInstallerScript.renderFiles().getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void guardedProfileHasExactlyTheExistingHelperRuntimePackages() {
        assertEquals(Arrays.asList(DeploymentHelperInstallerScript.APT_PACKAGES.split(" ")),
            DeploymentAgentToolchainPrograms.packages("APT", "HELPER_RUNTIME"));
        assertEquals(Arrays.asList(DeploymentHelperInstallerScript.DNF_PACKAGES.split(" ")),
            DeploymentAgentToolchainPrograms.packages("DNF", "HELPER_RUNTIME"));
    }

    @Test
    void completeCanonicalInstallerBindsOperationAndEveryGuardedSourceByte() {
        String fragment = DeploymentAgentHelperInstallerScript.prerequisites(OPERATION);
        String script = DeploymentAgentHelperInstallerScript.render(OPERATION);
        assertEquals(DeploymentHelperInstallerScript.renderFiles(fragment), script);
        assertEquals(OPERATION, DeploymentAgentHelperInstallerScript.parseOperation(script));
        assertTrue(fragment.contains(DeploymentAgentToolchainPrograms.dependencies()));
        assertTrue(fragment.indexOf("files_trusted_runtime_path /usr/bin/python3")
            < fragment.indexOf("  /usr/bin/python3 -I -S - 'HELPER_RUNTIME'"));
        String envelope = script.substring(0, script.indexOf("prepare_stage=helper-files-installation"));
        assertTrue(envelope.contains("if [ \"$runtime_missing\" -ne 0 ]; then\n" + fragment + "fi\n"));
        assertFalse(envelope.contains("if command -v apt-get"));
        assertFalse(envelope.contains("apt-get -o DPkg::Lock::Timeout=300"));
        assertFalse(envelope.contains("dnf install -y"));
    }

    @Test
    void operationFrameAndSourceTamperingAreRejectedWithoutEchoingInputs() {
        String script = DeploymentAgentHelperInstallerScript.render(OPERATION);
        for (String changed : new String[]{script + "echo extra-effect\n", script.replace(OPERATION, "INVALID-OPERATION"),
            script.replace("'" + OPERATION + "' <<", "'" + OPERATION + "' '--extra' <<"),
            script.replace("DEPENDENCY_PACKAGES = json.loads(", "DEPENDENCY_PACKAGES = altered("),
            script.replace("\n", "\r\n")})
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentHelperInstallerScript.parseOperation(changed));
        for (String invalid : new String[]{"", "a".repeat(31), "A".repeat(32), OPERATION + " --extra"})
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentHelperInstallerScript.prerequisites(invalid));
    }

    @Test
    void absentOrUntrustedPythonFailsBeforeAnyTransactionOrFileInstallation() throws Exception {
        Path bash = Path.of(System.getProperty("managed.test.bash", "E:/Program/Git/bin/bash.exe"));
        assertTrue(Files.isExecutable(bash), "The controlled bootstrap fixture requires Bash");
        Path fixture = temporary.resolve("python-required.sh");
        Files.writeString(fixture, "set -euo pipefail\n"
            + "files_trusted_runtime_path() { [ \"$1\" = /usr/bin/python3 ]; return 1; }\n"
            + "apt-get() { printf transaction > transaction; }\ndnf() { printf transaction > transaction; }\n"
            + "runtime_missing=1\nif [ \"$runtime_missing\" -ne 0 ]; then\n"
            + DeploymentAgentHelperInstallerScript.prerequisites(OPERATION) + "fi\n"
            + "printf installed > helper-files\n");
        var process = new ProcessBuilder(bash.toString(), "--noprofile", "--norc", fixture.toString())
            .directory(temporary.toFile()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Controlled Python prerequisite check must terminate");
            assertEquals(64, process.exitValue());
            assertEquals("PREPARE_REJECT=agent-bootstrap-python-required\n",
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertFalse(Files.exists(temporary.resolve("transaction")));
            assertFalse(Files.exists(temporary.resolve("helper-files")));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Controlled Bash process must exit");
            }
        }
    }
}
