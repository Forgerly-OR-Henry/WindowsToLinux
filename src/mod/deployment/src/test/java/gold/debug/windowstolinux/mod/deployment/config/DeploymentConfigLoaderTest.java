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
    @Test
    void generationRecoveryBudgetIsExplicitBoundedAndCanBeDisabled() throws Exception {
        String yaml = bundledYaml();
        for (int count : java.util.List.of(0, 1, 16))
            assertEquals(count, parse(yaml.replace("generation-recovery-attempts: 1", "generation-recovery-attempts: " + count)).ai().requests().generationRecoveryAttempts());
        for (String invalid : java.util.List.of("-1", "17", "null", "true", "1.2", "'1'", "2147483648"))
            assertThrows(IOException.class, () -> parse(yaml.replace("generation-recovery-attempts: 1", "generation-recovery-attempts: " + invalid)));
        String line = yaml.lines().filter(value -> value.contains("generation-recovery-attempts:")).findFirst().orElseThrow();
        assertThrows(IOException.class, () -> parse(yaml.replace(line + "\n", "")));
        assertThrows(IOException.class, () -> parse(yaml.replace(line, line + "\n" + line)));
        assertEquals(0, new DeploymentConfig.Requests(120, 120, 600).generationRecoveryAttempts());
    }

    private static String bundledYaml() throws IOException {
        try (var input = DeploymentConfigLoaderTest.class.getResourceAsStream(DeploymentConfigLoader.RESOURCE)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").replaceAll("(?m)[ \t]+#.*$", "");
        }
    }

    private static DeploymentConfig parse(String yaml) throws IOException {
        return DeploymentConfigLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void bundledConfigurationUsesCurrentDefaultsAndIsShared() throws Exception {
        var config = parse(bundledYaml());
        assertEquals(new DeploymentConfig.Source(2147483648L, 100000, 2147483648L), config.source());
        assertEquals(new DeploymentConfig.Build(1800, 1024, 4096, 4194304, 4294967296L), config.build());
        assertEquals(DeploymentConfig.Environment.original(), config.environment());
        assertEquals(new DeploymentConfig.View(750, 200), config.view());
        assertEquals(new DeploymentConfig.Ai(32768, 65536, 32768, new DeploymentConfig.Requests(120, 600, 600, 1),
            new DeploymentConfig.Execution(4096, 256, 128, 512, 4096, 4096), new DeploymentConfig.History(24, 131072, 262144, 2, 8192)), config.ai());
        assertEquals(new DeploymentConfig.RemotePaths("/usr/local/lib/windowstolinux",
            "/opt/windowstolinux/apps", "/var/lib/windowstolinux", "/etc/opt/windowstolinux/apps",
            "/var/opt/windowstolinux/apps"), config.paths());
        assertEquals(DeploymentConfig.RemotePaths.original(), config.paths());
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

    @Test
    void nonDefaultRemotePathsAreKeptAsLiteralValues() throws Exception {
        String yaml = bundledYaml().replace("helper-directory: /usr/local/lib/windowstolinux", "helper-directory: /srv/WTL-helper_1")
            .replace("applications-root: /opt/windowstolinux/apps", "applications-root: /srv/WTL-apps_1")
            .replace("state-root: /var/lib/windowstolinux", "state-root: /srv/WTL-state_1")
            .replace("configuration-root: /etc/opt/windowstolinux/apps", "configuration-root: /srv/WTL-config_1")
            .replace("data-root: /var/opt/windowstolinux/apps", "data-root: /srv/WTL-data_1");
        assertEquals(new DeploymentConfig.RemotePaths("/srv/WTL-helper_1", "/srv/WTL-apps_1",
            "/srv/WTL-state_1", "/srv/WTL-config_1", "/srv/WTL-data_1"), parse(yaml).paths());
    }

    @Test
    void pathsRequireExactlyFiveDirectFieldsAndStringTypes() throws Exception {
        String yaml = bundledYaml();
        String paths = yaml.substring(yaml.indexOf("paths:\n"));
        String withoutPaths = yaml.replace(paths, "");
        for (String invalid : java.util.List.of(withoutPaths, withoutPaths + "paths: null\n", withoutPaths + "paths: []\n",
            withoutPaths + "paths: {}\n", withoutPaths + "paths:\n  remote: {}\n",
            yaml + "  local: {}\n", yaml + "  extra: /srv/extra\n", yaml + "paths: {}\n"))
            assertThrows(IOException.class, () -> parse(invalid));
        for (String field : java.util.List.of("helper-directory", "applications-root", "state-root", "configuration-root", "data-root")) {
            String line = paths.lines().filter(value -> value.startsWith("  " + field + ": ")).findFirst().orElseThrow();
            assertThrows(IOException.class, () -> parse(yaml.replace(line + "\n", "")));
            for (String value : java.util.List.of("null", "12", "true", "[]", "{}"))
                assertThrows(IOException.class, () -> parse(yaml.replace(line, "  " + field + ": " + value)), field + "=" + value);
            assertThrows(IOException.class, () -> parse(yaml.replace(line, line + "\n" + line)), field);
        }
    }

    @Test
    void invalidRemotePathValuesAreReportedAsConfigurationErrors() throws Exception {
        String yaml = bundledYaml();
        for (String invalid : java.util.List.of(
            yaml.replace("helper-directory: /usr/local/lib/windowstolinux", "helper-directory: '/srv/helper/'"),
            yaml.replace("state-root: /var/lib/windowstolinux", "state-root: '/srv/${HOME}'"),
            yaml.replace("configuration-root: /etc/opt/windowstolinux/apps", "configuration-root: /opt/windowstolinux/apps"),
            yaml.replace("data-root: /var/opt/windowstolinux/apps", "data-root: /etc/opt/windowstolinux/apps/data"),
            yaml.replace("helper-directory: /usr/local/lib/windowstolinux", "helper-directory: /var/lib"))) {
            var failure = assertThrows(IOException.class, () -> parse(invalid));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
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
        var config = parse(yaml.replace("deployment-max-output-tokens: 32768", "deployment-max-output-tokens: 4096")
            .replace("extended-deployment-max-output-tokens: 65536", "extended-deployment-max-output-tokens: 12288")
            .replace("approval-max-output-tokens: 32768", "approval-max-output-tokens: 2048"));
        assertEquals(4096, config.ai().deploymentMaxOutputTokens());
        assertEquals(12288, config.ai().extendedDeploymentMaxOutputTokens());
        assertEquals(2048, config.ai().approvalMaxOutputTokens());
        for (String invalid : java.util.List.of(
            yaml.replace("approval-max-output-tokens: 32768", "approval-max-output-tokens: 32769"),
            yaml.replace("approval-max-output-tokens: 32768", "approval-max-output-tokens: 0"),
            yaml.replace("approval-max-output-tokens: 32768", "approval-max-output-tokens: '8192'"),
            yaml.replace("approval-max-output-tokens: 32768", "approval-max-output-tokens: 2147483648"),
            yaml.replace("extended-deployment-max-output-tokens: 65536", "extended-deployment-max-output-tokens: 8191"),
            yaml.replace("  approval-max-output-tokens: 32768\n", "")))
            assertThrows(IOException.class, () -> parse(invalid));
    }

    @Test
    void missingOrOversizedResourceIsRejected() {
        assertThrows(IOException.class, () -> DeploymentConfigLoader.load(null));
        assertThrows(IOException.class, () -> DeploymentConfigLoader.load(new ByteArrayInputStream(new byte[65537])));
    }

    @Test
    void runtimeBudgetsRejectMissingUnknownDuplicateOverflowAndInvalidRelationships() throws Exception {
        String yaml = bundledYaml();
        var larger = parse(yaml.replace("shared-tool-attempts: 4096", "shared-tool-attempts: 8192")
            .replace("standalone-tool-attempts: 256", "standalone-tool-attempts: 512")
            .replace("local-tool-turns: 128", "local-tool-turns: 256")
            .replace("agent-model-responses: 512", "agent-model-responses: 1024")
            .replace("retained-review-sessions: 4096", "retained-review-sessions: 8192")
            .replace("characters: 131072", "characters: 262144").replace("payload-bytes: 262144", "payload-bytes: 524288"));
        assertEquals(new DeploymentConfig.Execution(8192, 512, 256, 1024, 8192, 4096), larger.ai().execution());
        assertEquals(262144, larger.ai().history().characters());
        for (String field : java.util.List.of("deployment-timeout-seconds", "approval-timeout-seconds", "extended-timeout-seconds",
            "shared-tool-attempts", "standalone-tool-attempts", "local-tool-turns", "agent-model-responses", "retained-review-sessions", "max-observations",
            "max-batches", "characters", "payload-bytes", "initial-batches", "initial-payload-bytes")) {
            String line = yaml.lines().filter(value -> value.stripLeading().startsWith(field + ":")).findFirst().orElseThrow();
            assertThrows(IOException.class, () -> parse(yaml.replace(line + "\n", "")));
            assertThrows(IOException.class, () -> parse(yaml.replace(line, line + "\n" + line)));
            for (String invalid : java.util.List.of("0", "-1", "null", "true", "1.2", "'128'", "2147483648", "9223372036854775808"))
                assertThrows(IOException.class, () -> parse(yaml.replace(line, "    " + field + ": " + invalid)));
        }
        for (String invalid : java.util.List.of(
            yaml.replace("requests:", "requests:\n    unknown: 1"),
            yaml.replace("execution:", "execution:\n    unknown: 1"), yaml.replace("history:", "history:\n    unknown: 1"),
            yaml.replace("extended-timeout-seconds: 600", "extended-timeout-seconds: 119"),
            yaml.replace("standalone-tool-attempts: 256", "standalone-tool-attempts: 4097"),
            yaml.replace("local-tool-turns: 128", "local-tool-turns: 257"),
            yaml.replace("retained-review-sessions: 4096", "retained-review-sessions: 4097"),
            yaml.replace("agent-model-responses: 512", "agent-model-responses: 2147483647"),
            yaml.replace("max-observations: 4096", "max-observations: 1000000"),
            yaml.replace("characters: 131072", "characters: 1048577"),
            yaml.replace("payload-bytes: 262144", "payload-bytes: 1048577"),
            yaml.replace("initial-batches: 2", "initial-batches: 25"),
            yaml.replace("initial-payload-bytes: 8192", "initial-payload-bytes: 262145")))
            assertThrows(IOException.class, () -> parse(invalid));
    }
}
