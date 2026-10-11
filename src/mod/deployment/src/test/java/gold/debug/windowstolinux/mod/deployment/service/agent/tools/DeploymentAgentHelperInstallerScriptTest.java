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

    @Test
    void isolatedLauncherRejectsInheritedStartupCodeFunctionsAndOptions() throws Exception {
        Path startup = temporary.resolve("malicious-bash-env.sh");
        Files.writeString(startup, "printf startup > bash-env-marker\n");
        String inherited = runStartupProbe("exec /bin/bash --noprofile --norc -s", startup);
        assertTrue(Files.exists(temporary.resolve("bash-env-marker")), inherited);
        assertTrue(Files.exists(temporary.resolve("function-marker")), inherited);
        assertTrue(inherited.contains("EXTGLOB=on\n"), inherited);
        assertTrue(inherited.contains("XTRACE=on\n"), inherited);
        assertTrue(inherited.contains("LC_ALL=POSIX\n"), inherited);
        assertTrue(inherited.contains("PATH=/startup-untrusted:/usr/bin:/bin\n"), inherited);
        Files.delete(temporary.resolve("bash-env-marker"));
        Files.delete(temporary.resolve("function-marker"));

        String isolated = runStartupProbe(DeploymentAgentHelperInstallerScript.LAUNCHER, startup);
        assertFalse(Files.exists(temporary.resolve("bash-env-marker")), isolated);
        assertFalse(Files.exists(temporary.resolve("function-marker")), isolated);
        assertEquals("LC_ALL=C\nPATH=/usr/sbin:/usr/bin:/sbin:/bin\nEXTGLOB=off\nXTRACE=off\nLD_LIBRARY_PATH=unset\n",
            isolated);
    }

    private String runStartupProbe(String launcher, Path startup) throws Exception {
        Path bash = Path.of(System.getProperty("managed.test.bash", "E:/Program/Git/bin/bash.exe"));
        assertTrue(Files.isExecutable(bash), "The controlled startup fixture requires Bash");
        String inheritedStartup = "set +p\nexport BASH_ENV=\"$PWD/" + startup.getFileName() + "\"\n"
            + "startup_probe() { printf function > function-marker; }; export -f startup_probe\n"
            + "shopt -s extglob; export BASHOPTS\n"
            + "export LC_ALL=POSIX PATH=/startup-untrusted:/usr/bin:/bin LD_LIBRARY_PATH=/startup-untrusted\n"
            + "set -x; export SHELLOPTS\n";
        var builder = new ProcessBuilder(bash.toString(), "--noprofile", "--norc", "-p", "-c", inheritedStartup + launcher)
            .directory(temporary.toFile()).redirectError(temporary.resolve("startup-trace.log").toFile());
        var environment = builder.environment();
        environment.put("BASH_ENV", startup.toString().replace('\\', '/'));
        environment.put("BASH_FUNC_startup_probe%%", "() { printf function > function-marker; }");
        environment.put("BASHOPTS", "extglob");
        environment.put("SHELLOPTS", "xtrace");
        environment.put("LC_ALL", "POSIX");
        environment.put("PATH", "/startup-untrusted:/usr/bin:/bin");
        environment.put("LD_LIBRARY_PATH", "/startup-untrusted");
        var process = builder.start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(("printf 'LC_ALL=%s\\nPATH=%s\\n' \"$LC_ALL\" \"$PATH\"\n"
                    + "if declare -F startup_probe >/dev/null; then startup_probe; fi\n"
                    + "if shopt -q extglob; then printf 'EXTGLOB=on\\n'; else printf 'EXTGLOB=off\\n'; fi\n"
                    + "case $- in *x*) printf 'XTRACE=on\\n';; *) printf 'XTRACE=off\\n';; esac\n"
                    + "printf 'LD_LIBRARY_PATH=%s\\n' \"${LD_LIBRARY_PATH-unset}\"\n").getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Controlled startup check must terminate");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Controlled Bash process must exit");
            }
        }
    }
}
