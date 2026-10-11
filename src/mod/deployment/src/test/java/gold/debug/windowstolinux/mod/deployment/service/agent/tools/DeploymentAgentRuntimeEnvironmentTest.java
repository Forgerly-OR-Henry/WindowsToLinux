package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishArguments;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigEntry;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigScopeType;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigValue;
import gold.debug.windowstolinux.mod.deployment.service.core.config.error.DeploymentConfigException;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentIdentityMode;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentWorkload;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.ToolchainBuildEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentAgentRuntimeEnvironmentTest {
    @TempDir
    Path root;

    @Test
    void mixedBindingsUseTheSameOrderedBuildAndRuntimePath() {
        var tools = mixedTools();
        var result = DeploymentAgentRuntimeEnvironment.entries(process(), List.of(), tools);
        String expected = "/tools/java/bin:/tools/node/bin:/tools/python/bin:/tools/dotnet:/usr/local/bin:/usr/bin:/bin";
        assertEquals(expected, result.getFirst().value().canonicalValue());
        assertEquals(DeploymentConfigScopeType.RUNTIME, result.getFirst().scope());
        assertEquals(expected, ToolchainBuildEnvironment.path(tools));
        assertTrue(ToolchainBuildEnvironment.render(tools).contains("export PATH='" + expected + "'\n"));
        var empty = new DeploymentResolvedToolchains("TEST", List.of());
        assertEquals("/usr/local/bin:/usr/bin:/bin", ToolchainBuildEnvironment.path(empty));
        assertEquals("", ToolchainBuildEnvironment.render(empty));
    }

    @Test
    void typedRuntimeAndEmptyBindingsPreserveTheOriginalEntries() {
        var original = List.of(text("LABEL", "original"));
        var typed = new DeploymentRuntimeDefinition.NodeService(22, new DeploymentHealthCheck.Process(30, 2));
        assertSame(original, DeploymentAgentRuntimeEnvironment.entries(typed, original, mixedTools()));
        assertSame(original, DeploymentAgentRuntimeEnvironment.entries(process(), original,
            new DeploymentResolvedToolchains("TEST", List.of())));
    }

    @Test
    void explicitRuntimePathPreservesItsOriginalEntryAndList() {
        var declared = text("PATH", "/declared/bin:/usr/bin:/bin");
        var original = List.of(text("LABEL", "original"), declared);
        var result = DeploymentAgentRuntimeEnvironment.entries(process(), original, mixedTools());
        assertSame(original, result);
        assertSame(declared, result.get(1));
        assertEquals(1, result.stream().filter(entry -> entry.key().equals("PATH")).count());
    }

    @Test
    void buildOnlyAndNonTextPathRequireCorrectedDeclarations() {
        var build = new DeploymentConfigEntry("PATH", DeploymentConfigScopeType.BUILD, new DeploymentConfigValue.Text("/build/bin"));
        var number = new DeploymentConfigEntry("PATH", DeploymentConfigScopeType.RUNTIME, new DeploymentConfigValue.Number(1));
        for (var declared : List.of(build, number)) {
            var original = List.of(declared);
            var failure = assertThrows(IllegalArgumentException.class,
                () -> DeploymentAgentRuntimeEnvironment.entries(process(), original, mixedTools()));
            assertTrue(failure.getMessage().contains("RUNTIME Text"));
            assertEquals(List.of(declared), original);
        }
    }

    @Test
    void derivedPathDoesNotMutateInputAndChangesTheConfigurationIdentity() {
        var original = new ArrayList<>(List.of(text("LABEL", "original")));
        var projected = DeploymentAgentRuntimeEnvironment.entries(process(), original, mixedTools());
        assertEquals(List.of(text("LABEL", "original")), original);
        assertEquals(2, projected.size());
        assertThrows(UnsupportedOperationException.class, projected::clear);
        var before = DeploymentConfigSnapshot.create("demo", 1, "agent", Instant.EPOCH, original);
        var after = DeploymentConfigSnapshot.create("demo", 1, "agent", Instant.EPOCH, projected);
        assertNotEquals(before.sha256(), after.sha256());
        assertEquals(ToolchainBuildEnvironment.path(mixedTools()),
            after.requireValue("PATH", DeploymentConfigScopeType.RUNTIME).canonicalValue());
    }

    @Test
    void projectionKeepsTheEntryAndTextBoundsWithoutTruncation() {
        var original = new ArrayList<DeploymentConfigEntry>();
        for (int index = 0; index < 63; index++)
            original.add(text("VALUE_" + index, "value"));
        assertEquals(64, DeploymentAgentRuntimeEnvironment.entries(process(), original, mixedTools()).size());
        original.add(text("VALUE_63", "value"));
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentAgentRuntimeEnvironment.entries(process(), original, mixedTools()));
        assertEquals(64, original.size());
        var longTools = new DeploymentResolvedToolchains("TEST", List.of(selection(DeploymentToolchainType.NODE,
            "/tools/" + "x".repeat(1024), DeploymentResolvedToolchains.OriginType.MANAGED)));
        var failure = assertThrows(DeploymentConfigException.class,
            () -> DeploymentAgentRuntimeEnvironment.entries(process(), List.of(), longTools));
        assertEquals("configuration.entry.text-invalid", failure.problem().eventCode());
    }

    @Test
    void generatedEnvironmentFileMakesBoundToolsDiscoverableInAnIsolatedLauncher() throws Exception {
        String fixture = root.resolve("tools").toString().replace('\\', '/');
        if (fixture.matches("^[A-Za-z]:/.*"))
            fixture = "/" + Character.toLowerCase(fixture.charAt(0)) + fixture.substring(2);
        var tools = new DeploymentResolvedToolchains("TEST", List.of(selection(DeploymentToolchainType.NODE, fixture,
            DeploymentResolvedToolchains.OriginType.MANAGED)));
        var projected = DeploymentAgentRuntimeEnvironment.entries(process(), List.of(), tools);
        var snapshot = DeploymentConfigSnapshot.create("demo", 1, "agent", Instant.EPOCH, projected);
        String environment = new String(DeploymentPublishArguments.configuration(snapshot, "systemd"), StandardCharsets.UTF_8);
        String script = """
            set -euo pipefail
            fixture='%s'
            [ ! -e "$fixture" ]
            mkdir -- "$fixture"
            trap 'rm -f -- "$fixture/bin/wtl-fixture-bound-tool" "$fixture/runtime.env" "$fixture/run.sh"; rmdir -- "$fixture/bin" "$fixture"' EXIT
            mkdir -- "$fixture/bin"
            cat > "$fixture/bin/wtl-fixture-bound-tool" <<'WTL_TOOL'
            #!/bin/sh
            printf 'BOUND_TOOL_FOUND\\n'
            WTL_TOOL
            chmod +x -- "$fixture/bin/wtl-fixture-bound-tool"
            cat > "$fixture/runtime.env" <<'WTL_ENV'
            %sWTL_ENV
            cat > "$fixture/run.sh" <<'WTL_RUN'
            #!/bin/bash
            set -euo pipefail
            set -a
            . "$1"
            set +a
            exec wtl-fixture-bound-tool
            WTL_RUN
            if env -i PATH=/usr/bin:/bin /bin/bash --noprofile --norc -c 'exec wtl-fixture-bound-tool' >/dev/null 2>&1; then
                exit 80
            else [ "$?" = 127 ]; fi
            env -i PATH=/usr/bin:/bin /bin/bash --noprofile --norc "$fixture/run.sh" "$fixture/runtime.env"
            """.formatted(fixture, environment);
        Path bash = Path.of(System.getProperty("managed.test.bash",
            System.getProperty("os.name").startsWith("Windows") ? "E:/Program/Git/bin/bash.exe" : "/bin/bash"));
        assertTrue(Files.isExecutable(bash), "The controlled runtime environment fixture requires Bash");
        var builder = new ProcessBuilder(bash.toString(), "--noprofile", "--norc", "-s").redirectErrorStream(true);
        builder.environment().remove("BASH_ENV");
        var child = builder.start();
        try {
            try (var input = child.getOutputStream()) {
                input.write(script.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Controlled runtime lookup must terminate");
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertEquals(0, child.exitValue(), output);
            assertEquals("BOUND_TOOL_FOUND\n", output);
        } finally {
            if (child.isAlive()) {
                var descendants = child.descendants().toList();
                for (var descendant : descendants)
                    descendant.destroyForcibly();
                child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Owned Bash process must exit");
                for (var descendant : descendants)
                    assertFalse(descendant.onExit().get(5, TimeUnit.SECONDS).isAlive(), "Owned lookup child must exit");
            }
        }
    }

    private static DeploymentConfigEntry text(String key, String value) {
        return new DeploymentConfigEntry(key, DeploymentConfigScopeType.RUNTIME, new DeploymentConfigValue.Text(value));
    }

    private static DeploymentRuntimeDefinition process() {
        return new DeploymentRuntimeDefinition.ManagedProcess(new DeploymentHealthCheck.Process(30, 2),
            DeploymentIdentityMode.SYSTEMD_STATIC, DeploymentWorkload.unspecified());
    }

    private static DeploymentResolvedToolchains mixedTools() {
        return new DeploymentResolvedToolchains("TEST", List.of(
            selection(DeploymentToolchainType.JAVA, "/tools/java", DeploymentResolvedToolchains.OriginType.MANAGED),
            selection(DeploymentToolchainType.NODE, "/tools/node", DeploymentResolvedToolchains.OriginType.SYSTEM),
            selection(DeploymentToolchainType.PYTHON, "/tools/python", DeploymentResolvedToolchains.OriginType.MANAGED),
            selection(DeploymentToolchainType.DOTNET, "/tools/dotnet", DeploymentResolvedToolchains.OriginType.SYSTEM)));
    }

    private static DeploymentResolvedToolchains.Selection selection(DeploymentToolchainType ecosystem, String directory,
                                                                    DeploymentResolvedToolchains.OriginType origin) {
        var version = DeploymentToolchainVersion.parse(ecosystem, "21.0.1").orElseThrow();
        var requirement = ToolchainRequirement.declared(ecosystem, version.text(), "fixture", ToolchainRequirement.PurposeType.RUNTIME);
        return new DeploymentResolvedToolchains.Selection(requirement, version, directory, origin, "fixture", "a".repeat(64));
    }
}
