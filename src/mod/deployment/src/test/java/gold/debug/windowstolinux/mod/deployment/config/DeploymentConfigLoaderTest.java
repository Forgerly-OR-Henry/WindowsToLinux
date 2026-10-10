package gold.debug.windowstolinux.mod.deployment.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DeploymentConfigLoaderTest {
    private static String bundledYaml() throws IOException {
        try (var input = DeploymentConfigLoaderTest.class.getResourceAsStream(DeploymentConfigLoader.RESOURCE)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static DeploymentConfig parse(String yaml) throws IOException {
        return DeploymentConfigLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void bundledConfigurationPreservesTheOriginalDefaultsAndIsShared() throws Exception {
        var config = parse(bundledYaml());
        assertEquals(new DeploymentConfig.Source(2147483648L, 100000, 2147483648L), config.source());
        assertEquals(new DeploymentConfig.Build(1800, 1024, 4096, 4194304, 4294967296L), config.build());
        assertEquals(DeploymentConfig.Environment.original(), config.environment());
        assertEquals(new DeploymentConfig.View(750, 200), config.view());
        assertEquals(new DeploymentConfig.Ai(8192, 65536, 8192), config.ai());
        assertEquals(config, DeploymentConfigLoader.current());
        assertSame(DeploymentConfigLoader.current(), DeploymentConfigLoader.current());
    }

    @Test
    void nonDefaultConfigurationUsesTheExistingNonRootBuildContract() throws Exception {
        var yaml = bundledYaml().replace("1800", "120").replace("1024", "16").replace("4096", "512")
            .replace("4194304", "8192").replace("4294967296", "134217728")
            .replace("refresh-ms: 750", "refresh-ms: 1200").replace("page-size: 200", "page-size: 17");
        var config = parse(yaml);
        var limits = config.build().limits();
        assertEquals(120, limits.timeoutSeconds());
        assertEquals(16, limits.maxProcesses());
        assertEquals(512, limits.maxMemoryMiB());
        assertEquals(8192, limits.maxOutputBytes());
        assertEquals(134217728, limits.maxWorkspaceBytes());
        assertFalse(limits.runAsRoot());
        assertEquals(new DeploymentConfig.View(1200, 17), config.view());
    }

    static Stream<String> invalidYaml() throws IOException {
        String yaml = bundledYaml();
        return Stream.of("", yaml.replace("source:", "unknown:"), yaml + "unknown: 1\n",
            yaml.replace("  entries: 100000\n", ""), yaml.replace("entries: 100000", "entries: null"),
            yaml.replace("entries: 100000", "entries: '100000'"), yaml.replace("entries: 100000", "entries: 1.5"),
            yaml.replace("entries: 100000", "entries: 100000\n  entries: 100000"),
            yaml.replace("entries: 100000", "entries: 2147483648"),
            yaml.replace("bytes: 2147483648", "bytes: 9223372036854775808"),
            yaml.replace("entries: 100000", "entries: 0"), yaml.replace("entries: 100000", "entries: 100001"),
            yaml.replace("timeout-seconds: 1800", "timeout-seconds: 59"),
            yaml.replace("processes: 1024", "processes: 4097"), yaml.replace("memory-mib: 4096", "memory-mib: 255"),
            yaml.replace("output-bytes: 4194304", "output-bytes: 4095"),
            yaml.replace("workspace-bytes: 4294967296", "workspace-bytes: 67108863"),
            yaml.replace("install-seconds: 900", "install-seconds: 7201"),
            yaml.replace("check-seconds: 30", "check-seconds: 0"),
            yaml.replace("poll-seconds: 5", "poll-seconds: 901"),
            yaml.replace("refresh-ms: 750", "refresh-ms: 99"), yaml.replace("page-size: 200", "page-size: 201"),
            yaml + "---\nsource: {}\n");
    }

    @ParameterizedTest
    @MethodSource("invalidYaml")
    void invalidConfigurationNeverSilentlySuppliesDefaults(String yaml) {
        assertThrows(IOException.class, () -> parse(yaml));
    }

    @Test
    void outputBudgetsAreStrictAndApprovalCannotExceedDeployment() throws Exception {
        String yaml = bundledYaml();
        var config = parse(yaml.replace("deployment-max-output-tokens: 8192", "deployment-max-output-tokens: 4096")
            .replace("extended-deployment-max-output-tokens: 65536", "extended-deployment-max-output-tokens: 12288")
            .replace("approval-max-output-tokens: 8192", "approval-max-output-tokens: 2048"));
        assertEquals(new DeploymentConfig.Ai(4096, 12288, 2048), config.ai());
        for (String invalid : java.util.List.of(
            yaml.replace("approval-max-output-tokens: 8192", "approval-max-output-tokens: 8193"),
            yaml.replace("approval-max-output-tokens: 8192", "approval-max-output-tokens: 0"),
            yaml.replace("approval-max-output-tokens: 8192", "approval-max-output-tokens: '8192'"),
            yaml.replace("approval-max-output-tokens: 8192", "approval-max-output-tokens: 2147483648"),
            yaml.replace("extended-deployment-max-output-tokens: 65536", "extended-deployment-max-output-tokens: 8191"),
            yaml.replace("  approval-max-output-tokens: 8192\n", "")))
            assertThrows(IOException.class, () -> parse(invalid));
    }

    @Test
    void missingOrOversizedResourceIsRejected() {
        assertThrows(IOException.class, () -> DeploymentConfigLoader.load(null));
        assertThrows(IOException.class, () -> DeploymentConfigLoader.load(new ByteArrayInputStream(new byte[65537])));
    }
}
