package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import org.junit.jupiter.api.Test;

public class DeploymentAgentToolchainReleaseTest {
    private static final String OPERATION = "a".repeat(32);

    @Test
    void receiptIsBoundToCurrentLookupSourceOperationAndSelectedVersion() {
        String encoded = encoded("RUST", "1.95", "1.95.0", OPERATION);
        var release = DeploymentAgentToolchainRelease.decode(encoded, DeploymentToolchainType.RUST, "1.95", "1.95.0", OPERATION);
        assertEquals("1.95.0", release.get("version"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainRelease.decode(encoded, DeploymentToolchainType.RUST, "1.95", "-", OPERATION));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainRelease.decode(encoded, DeploymentToolchainType.RUST, "1.95", "1.95.0", "b".repeat(32)));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainRelease.decode(encoded + "\n", DeploymentToolchainType.RUST, "1.95", "1.95.0", OPERATION));
    }

    @Test
    void alteredSourceUrlDigestLayoutAndExtraFieldsAreRejected() {
        for (var change : Map.of("resolverSourceSha256", "b".repeat(64), "url", "https://example.org/archive", "digest", "abc", "layout", "flat", "version", "1.94.0", "extra", "value").entrySet()) {
            var values = values("RUST", "1.95", "1.95.0", OPERATION);
            values.put(change.getKey(), change.getValue());
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainRelease.decode(encode(values), DeploymentToolchainType.RUST, "1.95", "1.95.0", OPERATION));
        }
        for (String url : List.of("/archive", "https://user:password@static.rust-lang.org/archive", "https://static.rust-lang.org/archive#other")) {
            var values = values("RUST", "1.95", "1.95.0", OPERATION);
            values.put("url", url);
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainRelease.decode(encode(values), DeploymentToolchainType.RUST, "1.95", "1.95.0", OPERATION));
        }
    }

    @Test
    void officialSha512RemainsThePinnedDigestForDotnet() {
        var release = DeploymentAgentToolchainRelease.decode(encoded("DOTNET", "8", "8.0.100", OPERATION), DeploymentToolchainType.DOTNET, "8", "8.0.100", OPERATION);
        assertEquals("sha512", release.get("algorithm"));
        assertEquals(128, release.get("digest").length());
    }

    @Test
    void equivalentJavaBuildNotationKeepsTheOriginalExactRequestBinding() {
        var values = values("JAVA", "21", "21.0.5-b11", OPERATION);
        values.put("version", "21.0.5+11");
        var release = DeploymentAgentToolchainRelease.decode(encode(values), DeploymentToolchainType.JAVA, "21", "21.0.5-b11", OPERATION);
        assertEquals("21.0.5-b11", release.get("requestedExact"));
        assertEquals("21.0.5+11", release.get("version"));
        values.put("version", "21.0.5+12");
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainRelease.decode(encode(values), DeploymentToolchainType.JAVA, "21", "21.0.5-b11", OPERATION));
        values.put("version", "21.0.6+11");
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainRelease.decode(encode(values), DeploymentToolchainType.JAVA, "21", "21.0.5-b11", OPERATION));
        values.put("requestedExact", "21.0.5");
        values.put("version", "21.0.5+11");
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainRelease.decode(encode(values), DeploymentToolchainType.JAVA, "21", "21.0.5", OPERATION));
    }

    public static String encoded(String ecosystem, String branch, String exact, String operation) {
        return encode(values(ecosystem, branch, exact, operation));
    }

    private static TreeMap<String, String> values(String ecosystem, String branch, String exact, String operation) {
        var values = new TreeMap<String, String>();
        values.put("format", "WTL_AGENT_RELEASE_1");
        values.put("operation", operation);
        values.put("ecosystem", ecosystem);
        values.put("branch", branch);
        values.put("requestedExact", exact);
        values.put("version", exact.equals("-") ? branch + ".0" : exact);
        values.put("url", ecosystem.equals("JAVA") ? "https://api.adoptium.net/archive" : ecosystem.equals("PYTHON") ? "https://www.python.org/archive" : ecosystem.equals("DOTNET") ? "https://builds.dotnet.microsoft.com/archive" : "https://static.rust-lang.org/archive");
        values.put("algorithm", ecosystem.equals("DOTNET") ? "sha512" : "sha256");
        values.put("digest", "c".repeat(ecosystem.equals("DOTNET") ? 128 : 64));
        values.put("layout", ecosystem.equals("JAVA") ? "archive" : ecosystem.equals("PYTHON") ? "python" : ecosystem.equals("DOTNET") ? "flat" : "rust");
        values.put("platform", "linux-x86_64");
        values.put("resolverSourceSha256", DeploymentAgentToolchainPrograms.sha256(DeploymentAgentToolchainPrograms.resolver()));
        return values;
    }

    private static String encode(Map<String, String> values) {
        try {
            return Base64.getEncoder().encodeToString(new ObjectMapper().writeValueAsBytes(values));
        } catch (java.io.IOException invalid) {
            throw new AssertionError(invalid);
        }
    }
}
