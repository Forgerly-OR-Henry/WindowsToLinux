package gold.debug.windowstolinux.mod.deployment.linux.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentEcosystemToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.DeploymentCpuMicroarchitectureLevel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.LinuxDistroType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxFirewallKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxFirewallState;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxSecurityModuleType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxSecurityState;
import org.junit.jupiter.api.Test;

class DeploymentPlatformCapabilityParserTest {
    @Test
    void rendersSyntacticallyValidIsolatedHostAndSelectedContainerProbes() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of(bash)));
        var scripts = new java.util.ArrayList<String>();
        scripts.add(DeploymentPlatformCapabilityProbe.render());
        for (var engine : gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition.ContainerEngineType.values())
            scripts.add(DeploymentPlatformCapabilityProbe.renderForContainer(engine));
        for (String script : scripts) {
            var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-n").redirectErrorStream(true).start();
            try {
                try (var input = process.getOutputStream()) {
                    input.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue(), output);
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
            }
        }
    }

    @Test
    void startsProbeOutsideProjectWithoutInheritedHooksOrToolConfiguration() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of(bash)));
        var directory = java.nio.file.Files.createTempDirectory("probe-environment-");
        var hook = directory.resolve("startup-hook");
        var marker = directory.resolve("executed");
        try {
            String markerPath = marker.toString().replace('\\', '/').replace("'", "'\"'\"'");
            java.nio.file.Files.writeString(hook, "printf executed >'" + markerPath + "'\n");
            String script = "export BASH_ENV='" + hook.toString().replace('\\', '/').replace("'", "'\"'\"'") + "'\n"
                + "export NODE_OPTIONS=--require=untrusted JAVA_TOOL_OPTIONS=untrusted MAVEN_OPTS=untrusted PYTHONPATH=untrusted\n"
                + "npm() { printf executed >'" + markerPath + "'; }; export -f npm\n"
                + DeploymentPlatformCapabilityProbe.isolatedProbe("""
                set -eu
                [ "$PWD" = / ] && [ "$HOME" = /dev/null ]
                for variable in BASH_ENV NODE_OPTIONS JAVA_TOOL_OPTIONS MAVEN_OPTS PYTHONPATH; do
                  [ -z "${!variable+x}" ] || exit 91
                done
                ! declare -F npm >/dev/null || exit 92
                [ "$MAVEN_SKIP_RC" = 1 ] && [ "$MAVEN_BASEDIR" = / ]
                [ "$NPM_CONFIG_USERCONFIG" = /dev/null ] && [ "$NPM_CONFIG_GLOBALCONFIG" = /dev/null ]
                [ "$YARN_IGNORE_PATH" = 1 ] && [ "$COREPACK_ENABLE_NETWORK" = 0 ] && [ "$COREPACK_ENABLE_PROJECT_SPEC" = 0 ]
                printf 'ISOLATED=1\\n'
                """);
            var builder = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").directory(directory.toFile()).redirectErrorStream(true);
            builder.environment().remove("BASH_ENV");
            var process = builder.start();
            try {
                try (var input = process.getOutputStream()) {
                    input.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue(), output);
                assertEquals("ISOLATED=1\n", output.replace("\r\n", "\n"));
                assertFalse(java.nio.file.Files.exists(marker));
                assertTrue(DeploymentPlatformCapabilityProbe.render().contains("cd -- /\n"));
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
            }
        } finally {
            java.nio.file.Files.deleteIfExists(marker);
            java.nio.file.Files.deleteIfExists(hook);
            java.nio.file.Files.deleteIfExists(directory);
        }
    }

    @Test
    void skipsConfiguredPackageManagersAndTreatsUnavailableOfflineVersionsAsUnknown() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of(bash)));
        String tools = DeploymentEcosystemCapabilityProbe.platformToolChecks();
        String packageProbe = tools.substring(tools.indexOf("package_probe_safe=1"), tools.indexOf("printf 'TOOL_PYTHON='"));
        var directory = java.nio.file.Files.createTempDirectory("probe-project-config-");
        var config = directory.resolve(".mvn");
        var marker = directory.resolve("executed");
        String path = directory.toString().replace('\\', '/');
        if (path.matches("^[A-Za-z]:/.*")) path = "/" + Character.toLowerCase(path.charAt(0)) + path.substring(2);
        try {
            for (String scenario : java.util.List.of("configured", "safe", "offline")) {
                java.nio.file.Files.deleteIfExists(config);
                java.nio.file.Files.deleteIfExists(marker);
                if (scenario.equals("configured"))
                    java.nio.file.Files.writeString(config, "untrusted extension configuration");
                String script = "set -eu\nscenario='" + scenario + "'\nmarker='" + path.replace("'", "'\"'\"'") + "/executed'\n"
                    + """
                    command() { [ "$1" = -v ]; }
                    npm() { printf called >>"$marker"; [ "$scenario" != offline ] || return 1; printf '11.0.0\\n'; }
                    pnpm() { npm "$@"; }
                    yarn() { npm "$@"; }
                    mvn() { printf called >>"$marker"; printf 'Apache Maven 3.9.12\\n'; }
                    """ + packageProbe.replace("/.mvn", path + "/.mvn");
                var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start();
                try {
                    try (var input = process.getOutputStream()) {
                        input.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                    String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
                    assertEquals(0, process.exitValue(), output);
                    assertEquals(!scenario.equals("configured"), java.nio.file.Files.exists(marker));
                    String version = scenario.equals("safe") ? "11.0.0" : "";
                    assertTrue(output.contains("TOOL_NPM=" + version + "\nTOOL_PNPM=" + version + "\nTOOL_YARN=" + version + "\n"), output);
                    assertTrue(output.contains("TOOL_MAVEN=" + (scenario.equals("configured") ? "" : "3.9.12") + "\n"), output);
                } finally {
                    if (process.isAlive()) process.destroyForcibly().waitFor();
                }
            }
        } finally {
            java.nio.file.Files.deleteIfExists(marker);
            java.nio.file.Files.deleteIfExists(config);
            java.nio.file.Files.deleteIfExists(directory);
        }
    }

    @Test
    void readOnlyMatrixAvoidsContainerInitializationAndUsesRestrictedVersionQueries() throws Exception {
        String host = DeploymentPlatformCapabilityProbe.render();
        assertTrue(!host.contains("podman info") && !host.contains("docker info") && !host.contains("podman quadlet") && !host.contains("sudo -n"));
        var docker = DeploymentPlatformCapabilityProbe.renderForContainer(gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition.ContainerEngineType.DOCKER);
        var podman = DeploymentPlatformCapabilityProbe.renderForContainer(gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition.ContainerEngineType.PODMAN);
        assertTrue(docker.contains("docker info") && !docker.contains("podman info") && !docker.contains("podman quadlet"));
        assertTrue(podman.contains("podman info") && podman.contains("podman quadlet --help") && !podman.contains("docker info"));
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of(bash)));
        String script = """
            set -eu
            export PATH=/usr/bin:/bin
            command() { [ "$1" = -v ] && { [ "$2" = dotnet ] || [ "$2" = composer ]; }; }
            compgen() { :; }
            dotnet() { [ "$*" = --list-sdks ] || exit 91; printf '8.0.123 [/test/sdk]\\n'; }
            composer() { [ "$*" = '--no-plugins --no-scripts --no-cache --no-interaction --version' ] || exit 92; printf 'Composer version 2.8.1\\n'; }
            """ + DeploymentEcosystemCapabilityProbe.platformToolChecks();
        var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("SERVICE_DOTNET=8.0.123\n") && output.contains("TOOL_DOTNET=8.0.123\n"), output);
            assertTrue(output.contains("TOOL_COMPOSER=2.8.1\n"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    @Test
    void rejectsUnownedOrWritableProbeProgramsAndParents() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of(bash)));
        var file = java.nio.file.Files.createTempFile("probe-ownership-", ".bin");
        try {
            String path = file.toString().replace('\\', '/');
            if (path.matches("^[A-Za-z]:/.*")) path = "/" + Character.toLowerCase(path.charAt(0)) + path.substring(2);
            for (String scenario : java.util.List.of("safe", "unowned", "writable-file", "writable-parent", "writable-root", "writable-original-parent")) {
                String script = "set -eu\nfile='" + path.replace("'", "'\"'\"'")
                    + "'\nresolved=$(/usr/bin/readlink -f -- \"$file\")\nunsafe_parent=${resolved%/*}\nscenario=" + scenario + "\n"
                    + """
                    probe_stat() {
                      case "$2" in
                        %u) if [ "$scenario" = unowned ] && [ "$4" = "$file" ]; then printf '1000'; else printf '0'; fi ;;
                        %a) if { [ "$scenario" = writable-file ] && [ "$4" = "$file" ]; } || { { [ "$scenario" = writable-parent ] || [ "$scenario" = writable-original-parent ]; } && [ "$4" = "$unsafe_parent" ]; } || { [ "$scenario" = writable-root ] && [ "$4" = / ]; }; then printf '777'; else printf '755'; fi ;;
                        *) return 1 ;;
                      esac
                    }
                    probe_readlink() {
                      if [ "$scenario" = writable-original-parent ] && [ "$3" = "$file" ]; then printf '/usr/bin/true'; else /usr/bin/readlink "$@"; fi
                    }
                    """
                    + DeploymentPlatformCapabilityProbe.probeGuards().replace("/usr/bin/stat", "probe_stat").replace("/usr/bin/readlink", "probe_readlink")
                    + "if probe_file \"$file\"; then exit 0; else exit 1; fi\n";
                var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start();
                try {
                    try (var input = process.getOutputStream()) {
                        input.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                    String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    assertEquals(scenario.equals("safe") ? 0 : 1, process.exitValue(), scenario + ": " + output);
                } finally {
                    if (process.isAlive()) process.destroyForcibly().waitFor();
                }
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void readsPipMetadataWithoutExecutingInstalledPackagesOrSiteHooks() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        String python = System.getProperty("managed.test.python", "/usr/bin/python3");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of(bash))
            && java.nio.file.Files.isExecutable(java.nio.file.Path.of(python)));
        var directory = java.nio.file.Files.createTempDirectory("python-probe-shadow-");
        var marker = directory.resolve("shadow-executed");
        var installed = java.nio.file.Files.createDirectory(directory.resolve("site-packages"));
        var pip = java.nio.file.Files.createDirectory(installed.resolve("pip"));
        var metadata = java.nio.file.Files.createDirectory(installed.resolve("pip-25.1.dist-info"));
        try {
            String shadow = "from pathlib import Path\nPath(" + "'" + marker.toString().replace('\\', '/').replace("'", "\\'") + "'" + ").write_text('executed')\n";
            for (String module : java.util.List.of("pip", "venv"))
                java.nio.file.Files.writeString(directory.resolve(module + ".py"), shadow);
            java.nio.file.Files.writeString(pip.resolve("__init__.py"), shadow);
            java.nio.file.Files.writeString(installed.resolve("probe.pth"), "import pip\n");
            java.nio.file.Files.writeString(metadata.resolve("METADATA"), "Metadata-Version: 2.1\nName: pip\nVersion: 25.1\n");
            String program = python.replace('\\', '/');
            if (program.matches("^[A-Za-z]:/.*"))
                program = "/" + Character.toLowerCase(program.charAt(0)) + program.substring(2);
            String script = """
                set -eu
                export PATH=/usr/bin:/bin
                command() { [ "$1" = -v ] && { [ "$2" = python3 ] || [ "$2" = python3.12 ]; }; }
                compgen() { printf 'python3.12\\n'; }
                python3() { [ "$*" = '-I -S -B -' ] || exit 91; "$fixture_python" "$@"; }
                python3.12() { [ "$*" = '-I -S -B -m venv --help' ] || exit 92; "$fixture_python" "$@"; }
                """ + "fixture_python='" + program.replace("'", "'\"'\"'") + "'\n"
                + DeploymentEcosystemCapabilityProbe.platformToolChecks().replace("site.getsitepackages()",
                "[r'" + installed.toString().replace('\\', '/').replace("'", "\\'") + "']");
            var builder = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").directory(directory.toFile()).redirectErrorStream(true);
            builder.environment().put("PYTHONPATH", directory.toString());
            var process = builder.start();
            try {
                try (var input = process.getOutputStream()) {
                    input.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue(), output);
                assertTrue(output.contains("PYTHON_VERSIONS=3.12\n"), output);
                assertTrue(output.contains("TOOL_PIP=25.1\n"), output);
                assertTrue(!java.nio.file.Files.exists(marker), output);
                assertTrue(!java.nio.file.Files.exists(directory.resolve("__pycache__")), output);
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
            }
        } finally {
            java.nio.file.Files.deleteIfExists(metadata.resolve("METADATA"));
            java.nio.file.Files.deleteIfExists(metadata);
            java.nio.file.Files.deleteIfExists(pip.resolve("__init__.py"));
            java.nio.file.Files.deleteIfExists(pip);
            java.nio.file.Files.deleteIfExists(installed.resolve("probe.pth"));
            java.nio.file.Files.deleteIfExists(installed);
            for (String file : java.util.List.of("pip.py", "venv.py", "shadow-executed"))
                java.nio.file.Files.deleteIfExists(directory.resolve(file));
            java.nio.file.Files.deleteIfExists(directory);
        }
    }

    @Test
    void readsOsReleaseAsDataAndRejectsExecutableOrDuplicateValues() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of(bash)));
        var directory = java.nio.file.Files.createTempDirectory("os-release-data-");
        var metadata = directory.resolve("os-release");
        var marker = directory.resolve("executed");
        try {
            for (String content : java.util.List.of("ID=ubuntu\nVERSION_ID=\"24.04\"\nVARIANT_ID='stream'\n",
                "ID=$(printf injected >'" + marker.toString().replace('\\', '/') + "')\n", "ID=ubuntu\nID=debian\n")) {
                java.nio.file.Files.writeString(metadata, content);
                String script = "set -eu\n" + DeploymentPlatformCapabilityProbe.osReleaseReader()
                    + "file='" + metadata.toString().replace('\\', '/').replace("'", "'\"'\"'") + "'\n"
                    + "id=\"$(os_release_value ID \"$file\")\"\nprintf 'ID=%s\\n' \"$id\"\n"
                    + "version=\"$(os_release_value VERSION_ID \"$file\")\"\nprintf 'VERSION=%s\\n' \"$version\"\n";
                var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start();
                try {
                    try (var input = process.getOutputStream()) {
                        input.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                    String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    // Assignment preserves the parser's status; printf alone would hide a failed substitution. / 赋值保留解析器状态，printf会掩盖失败。
                    if (content.startsWith("ID=ubuntu\nVERSION")) {
                        assertEquals(0, process.exitValue(), output);
                        assertTrue(output.contains("ID=ubuntu\nVERSION=24.04\n"), output);
                    } else {
                        assertEquals(64, process.exitValue(), output);
                        assertTrue(!output.contains("ID=injected"), output);
                        assertTrue(!java.nio.file.Files.exists(marker));
                    }
                } finally {
                    if (process.isAlive()) process.destroyForcibly().waitFor();
                }
            }
        } finally {
            java.nio.file.Files.deleteIfExists(marker);
            java.nio.file.Files.deleteIfExists(metadata);
            java.nio.file.Files.deleteIfExists(directory);
        }
    }

    @Test
    void jarVersionFallbackNeverExecutesRejectedCompiler() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of(bash)));
        String tools = DeploymentEcosystemCapabilityProbe.platformToolChecks();
        String jarProbe = tools.substring(tools.indexOf("if command -v jar "), tools.indexOf("if command -v node ", tools.indexOf("if command -v jar ")))
            .replace("command -v ", "probe_command ");
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains(jarProbe));
        var directory = java.nio.file.Files.createTempDirectory("jar-fallback-");
        var marker = directory.resolve("compiler-executed");
        try {
            for (String scenario : java.util.List.of("jar-version", "fallback-safe", "fallback-denied", "jar-denied")) {
                java.nio.file.Files.deleteIfExists(marker);
                String script = "set -euo pipefail\nscenario='" + scenario + "'\nmarker='"
                    + marker.toString().replace('\\', '/').replace("'", "'\"'\"'") + "'\n" + """
                    probe_command() {
                      if [ "$1" = jar ]; then [ "$scenario" != jar-denied ];
                      else [ "$1" = javac ] && [ "$scenario" = fallback-safe ]; fi
                    }
                    jar() {
                      [ "$*" = --version ] || exit 92
                      if [ "$scenario" = jar-version ]; then printf 'jar 21.0.1\\n'; else return 1; fi
                    }
                    javac() {
                      printf 'called' > "$marker"
                      [ "$scenario" = fallback-safe ] && [ "$*" = -version ] || exit 91
                      printf 'javac 17.0.1\\n'
                    }
                    """ + jarProbe;
                var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start();
                try {
                    try (var input = process.getOutputStream()) {
                        input.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                    String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    assertEquals(0, process.exitValue(), scenario + ": " + output);
                    String expected = scenario.equals("jar-version") ? "21.0.1" : scenario.equals("fallback-safe") ? "17.0.1" : "";
                    assertEquals("TOOL_JAR=" + expected + "\n", output.replace("\r\n", "\n"));
                    assertEquals(scenario.equals("fallback-safe"), java.nio.file.Files.exists(marker), scenario);
                } finally {
                    if (process.isAlive()) process.destroyForcibly().waitFor();
                }
            }
        } finally {
            java.nio.file.Files.deleteIfExists(marker);
            java.nio.file.Files.deleteIfExists(directory);
        }
    }

    @Test
    void firewalldNeverExecutesRejectedSystemctl() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of(bash)));
        for (String scenario : java.util.List.of("active", "inactive", "untrusted")) {
            String script = """
                set -eu
                probe_command() {
                  [ "$1" = firewall-cmd ] || { [ "$1" = systemctl ] && [ "$scenario" != untrusted ]; }
                }
                systemctl() {
                  [ "$scenario" != untrusted ] || { printf 'REJECTED_TOOL_EXECUTED\\n'; exit 91; }
                  [ "$*" = 'is-active --quiet firewalld' ] || exit 92
                  printf 'VERIFIED_SYSTEMCTL_EXECUTED\\n'
                  [ "$scenario" = active ]
                }
                """ + "scenario='" + scenario + "'\n" + DeploymentPlatformCapabilityProbe.firewallProbe().replace("command -v ", "probe_command ");
            Process process = new ProcessBuilder(bash, "-s").redirectErrorStream(true).start();
            try {
                try (var input = process.getOutputStream()) {
                    input.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue(), output);
                assertTrue(output.contains("FIREWALL=firewalld\n"), output);
                assertTrue(output.contains("FIREWALL_STATE=" + (scenario.equals("untrusted") ? "unknown" : scenario) + "\n"), output);
                assertEquals(!scenario.equals("untrusted"), output.contains("VERIFIED_SYSTEMCTL_EXECUTED"), output);
                assertFalse(output.contains("REJECTED_TOOL_EXECUTED"), output);
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
            }
        }
    }

    @Test
    void readsUfwRulesInsteadOfTheOneshotServiceState() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of(bash)));
        for (String scenario : java.util.List.of("active", "inactive", "denied", "unexpected", "sudo")) {
            String script = """
                set -eu
                export LC_ALL=C
                command() { [ "$1" = -v ] && [ "$2" = ufw ]; }
                systemctl() { return 0; }
                ufw() {
                  case "$scenario" in
                    denied|sudo) return 1 ;;
                    unexpected) printf 'unrecognized output\\n' ;;
                    *) printf 'Status: %s\\n' "$scenario" ;;
                  esac
                }
                sudo() { [ "$scenario" = sudo ] && printf 'Status: inactive\\n'; }
                """ + "scenario='" + scenario + "'\n" + DeploymentPlatformCapabilityProbe.firewallProbe();
            Process process = new ProcessBuilder(bash, "-s").redirectErrorStream(true).start();
            try {
                try (var input = process.getOutputStream()) {
                    input.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue(), output);
                String expected = switch (scenario) {
                    case "active" -> "active";
                    case "inactive" -> "inactive";
                    default -> "unknown";
                };
                assertTrue(output.contains("FIREWALL=ufw\n"), output);
                assertTrue(output.contains("FIREWALL_STATE=" + expected + "\n"), output);
            } finally {
                if (process.isAlive())
                    process.destroyForcibly().waitFor();
            }
        }
    }

    @Test
    void parsesOnlyFixedNonSecretProbeFacts() {
        var capabilities = DeploymentPlatformCapabilityParser.fromValues(Map.ofEntries(Map.entry("DISTRO_ID", "centos"),
            Map.entry("DISTRO_VARIANT", "stream"), Map.entry("VERSION", "10"), Map.entry("ARCH", "x86_64"),
            Map.entry("PACKAGE_MANAGER", "dnf"), Map.entry("PACKAGE_ARCH", "x86_64"), Map.entry("SYSTEMD", "1"),
            Map.entry("DOCKER_CLIENT", "0"), Map.entry("DOCKER_OPERATIONAL", "0"), Map.entry("PODMAN_CLIENT", "1"),
            Map.entry("PODMAN_OPERATIONAL", "1"), Map.entry("PODMAN_QUADLET", "1"), Map.entry("JAVA_MAJORS", "21"),
            Map.entry("NODE_MAJORS", "22"), Map.entry("NPM", "1"), Map.entry("PYTHON3", "1"),
            Map.entry("PYTHON_VERSIONS", "3.11,3.12"), Map.entry("SERVICE_GO", "1.24"),
            Map.entry("SERVICE_RUST", "1.89.0"), Map.entry("SERVICE_DOTNET", "8.0.408"),
            Map.entry("SERVICE_KOTLIN", "21"), Map.entry("SERVICE_PHP", "8.3"), Map.entry("SERVICE_RUBY", "3.3.5"),
            Map.entry("TOOL_JAVAC", "21.0.8"), Map.entry("TOOL_JAR", "21.0.8"), Map.entry("TOOL_PNPM", "10.15.1"),
            Map.entry("TOOL_UV", "0.8.12"), Map.entry("TOOL_KOTLINC", "2.0.21"), Map.entry("TOOL_CMAKE", "3.31.6"),
            Map.entry("TOOL_NINJA", "1.12.1"), Map.entry("TOOL_C_COMPILER", "14.2.1"),
            Map.entry("TOOL_CPP_COMPILER", "14.2.1"), Map.entry("CPU_LEVEL", "x86-64-v3"),
            Map.entry("CPU_FLAGS", "sse4_2,popcnt"), Map.entry("SECURITY_MODULE", "selinux"),
            Map.entry("SECURITY_STATE", "enforcing"), Map.entry("FIREWALL", "firewalld"),
            Map.entry("FIREWALL_STATE", "active")), "SHA256:host");

        assertEquals(LinuxDistroType.CENTOS_STREAM, capabilities.distro());
        assertTrue(capabilities.podmanQuadletAvailable());
        assertTrue(capabilities.podmanOperational());
        assertEquals(java.util.Set.of(21), capabilities.javaMajorVersions());
        assertEquals(java.util.Set.of(22), capabilities.nodeMajorVersions());
        assertEquals(java.util.Set.of("3.11", "3.12"), capabilities.pythonVersions());
        assertTrue(capabilities.python3Available());
        assertTrue(capabilities.cpuFlags().contains("sse4_2"));
        assertEquals("x86_64", capabilities.packageArchitecture());
        assertEquals(DeploymentCpuMicroarchitectureLevel.X86_64_V3, capabilities.cpuMicroarchitecture());
        assertEquals(DeploymentLinuxSecurityModuleType.SELINUX, capabilities.securityPosture().module());
        assertEquals(DeploymentLinuxSecurityState.ENFORCING, capabilities.securityPosture().state());
        assertEquals(DeploymentLinuxFirewallKind.FIREWALLD, capabilities.securityPosture().firewall());
        assertEquals(DeploymentLinuxFirewallState.ACTIVE, capabilities.securityPosture().firewallState());
        assertEquals(java.util.Set.of("1.24"),
            capabilities.serviceRuntimeVersions().get(DeploymentProjectKind.GO_SERVICE));
        assertEquals(java.util.Set.of("3.3.5"),
            capabilities.serviceRuntimeVersions().get(DeploymentProjectKind.RUBY_SERVICE));
        assertEquals(java.util.Set.of("21.0.8"),
            capabilities.ecosystemToolVersions().get(DeploymentEcosystemToolType.JAVAC));
        assertEquals(java.util.Set.of("10.15.1"),
            capabilities.ecosystemToolVersions().get(DeploymentEcosystemToolType.PNPM));
        assertEquals(java.util.Set.of("2.0.21"),
            capabilities.ecosystemToolVersions().get(DeploymentEcosystemToolType.KOTLINC));
        assertEquals(java.util.Set.of("1.12.1"),
            capabilities.ecosystemToolVersions().get(DeploymentEcosystemToolType.NINJA));
        assertEquals(java.util.Set.of("14.2.1"),
            capabilities.ecosystemToolVersions().get(DeploymentEcosystemToolType.CPP_COMPILER));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("PODMAN_QUADLET"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("SERVICE_DOTNET"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("TOOL_KOTLINC"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("TOOL_CMAKE"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("TOOL_NINJA"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("printf(\"%d.%d\""));
        assertTrue(!DeploymentPlatformCapabilityProbe.render().contains("printf(\"%%d.%%d\""));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("SECURITY_MODULE"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("FIREWALL_STATE"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("nft list ruleset"));
        assertFalse(DeploymentPlatformCapabilityProbe.render().contains("/windowstolinux/java-21"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("probe_command java"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("PACKAGE_ARCH"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("CPU_LEVEL"));
        assertTrue(!DeploymentPlatformCapabilityProbe.render().contains("setenforce"));
        assertTrue(!DeploymentPlatformCapabilityProbe.render().contains("systemctl stop"));
        assertTrue(!DeploymentPlatformCapabilityProbe.render().contains("systemctl disable"));
    }

    @Test
    void parsesAnObservedInactiveNftablesRuleset() {
        var capabilities = DeploymentPlatformCapabilityParser
            .fromValues(
                Map.of("DISTRO_ID", "centos", "DISTRO_VARIANT", "", "VERSION", "9", "ARCH", "x86_64",
                    "PACKAGE_MANAGER", "dnf", "FIREWALL", "nftables", "FIREWALL_STATE", "inactive"),
                "SHA256:fixture");

        assertEquals(DeploymentLinuxFirewallKind.NFTABLES, capabilities.securityPosture().firewall());
        assertEquals(DeploymentLinuxFirewallState.INACTIVE, capabilities.securityPosture().firewallState());
    }

    @Test
    void classifiesEveryEnterpriseDistributionIndependently() {
        assertEquals(LinuxDistroType.DEBIAN, classify("debian", "", "13"));
        assertEquals(LinuxDistroType.CENTOS_STREAM, classify("centos", "", "9"));
        assertEquals(LinuxDistroType.CENTOS_STREAM, classify("centos", "stream", "10"));
        assertEquals(LinuxDistroType.LEGACY_CENTOS, classify("centos", "", "8"));
        assertEquals(LinuxDistroType.ROCKY_LINUX, classify("rocky", "", "9.8"));
        assertEquals(LinuxDistroType.ALMALINUX, classify("almalinux", "", "10.2"));
        assertEquals(LinuxDistroType.ORACLE_LINUX, classify("ol", "", "10.2"));
    }

    private static LinuxDistroType classify(String id, String variant, String version) {
        return DeploymentPlatformCapabilityParser.fromValues(Map.of("DISTRO_ID", id, "DISTRO_VARIANT", variant,
            "VERSION", version, "ARCH", "x86_64", "PACKAGE_MANAGER", "dnf"), "SHA256:fixture").distro();
    }
}
