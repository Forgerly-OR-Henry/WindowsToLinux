package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import java.net.URI;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion;

/** A canonical current resolver receipt, never a model-selected URL or permission. / 当前解析器的规范回执，不是模型选择的 URL 或许可。 */
final class DeploymentAgentToolchainRelease {
    private static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private static final Set<String> FIELDS = Set.of("format", "operation", "ecosystem", "branch", "requestedExact", "version", "url", "algorithm", "digest", "layout", "platform", "resolverSourceSha256");

    private static final Set<String> HOSTS = Set.of("api.adoptium.net", "github.com", "api.github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com", "nodejs.org", "go.dev", "dl.google.com", "static.rust-lang.org", "www.python.org", "www.php.net", "downloads.php.net", "museum.php.net", "www.ruby-lang.org", "cache.ruby-lang.org", "rubygems.org", "builds.dotnet.microsoft.com", "dotnetcli.blob.core.windows.net", "ci.dot.net");

    private static final Map<String, String> LAYOUTS = Map.of("JAVA", "archive", "NODE", "archive", "GO", "archive", "DOTNET", "flat", "KOTLIN", "archive", "RUST", "rust", "PYTHON", "python", "PHP", "php", "RUBY", "ruby");

    private DeploymentAgentToolchainRelease() {
    }

    static Map<String, String> decode(String encoded, DeploymentToolchainType ecosystem, String branch, String exact, String operation) {
        byte[] bytes = null;
        try {
            if (encoded == null || encoded.length() > 5464 || !operation.matches("[a-f0-9]{32}"))
                throw new IllegalArgumentException("Current release descriptor bounds changed");
            bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length > 4096 || !Base64.getEncoder().encodeToString(bytes).equals(encoded))
                throw new IllegalArgumentException("Noncanonical release encoding");
            var node = JSON.readTree(bytes);
            if (node == null || !node.isObject() || node.size() != FIELDS.size())
                throw new IllegalArgumentException("Release descriptor shape changed");
            var values = new TreeMap<String, String>();
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!FIELDS.contains(field.getKey()) || !field.getValue().isTextual())
                    throw new IllegalArgumentException("Release descriptor fields changed");
                values.put(field.getKey(), field.getValue().textValue());
            }
            if (!Arrays.equals(bytes, JSON.writeValueAsBytes(values)) || !values.get("format").equals("WTL_AGENT_RELEASE_1")
                || !values.get("operation").equals(operation) || !values.get("ecosystem").equals(ecosystem.name())
                || !values.get("branch").equals(branch) || !values.get("requestedExact").equals(exact)
                || !values.get("platform").equals("linux-x86_64")
                || !values.get("resolverSourceSha256").equals(DeploymentAgentToolchainPrograms.sha256(DeploymentAgentToolchainPrograms.resolver())))
                throw new IllegalArgumentException("Current release source or request binding changed");
            var version = DeploymentToolchainVersion.parse(ecosystem, values.get("version")).orElseThrow();
            if (version.preview() || !version.text().equals(values.get("version")) || !version.branch().equals(branch)
                || !exact.equals("-") && !version.sameRelease(DeploymentToolchainVersion.parse(ecosystem, exact).orElseThrow()))
                throw new IllegalArgumentException("Resolved release differs from the selected requirement");
            String url = values.get("url");
            var uri = URI.create(url);
            if (url.length() > 2048 || !url.matches("[!-~]+") || !"https".equals(uri.getScheme())
                || uri.getHost() == null || !HOSTS.contains(uri.getHost()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() != -1 && uri.getPort() != 443)
                throw new IllegalArgumentException("Resolved source is outside official HTTPS origins");
            String algorithm = values.get("algorithm");
            if (!Set.of("sha256", "sha512").contains(algorithm)
                || !values.get("digest").matches("[a-f0-9]{" + (algorithm.equals("sha256") ? 64 : 128) + "}")
                || !values.get("layout").equals(LAYOUTS.get(ecosystem.name())))
                throw new IllegalArgumentException("Resolved archive identity changed");
            return Collections.unmodifiableMap(values);
        } catch (java.io.IOException | NoSuchElementException malformed) {
            throw new IllegalArgumentException("Current release descriptor is invalid", malformed);
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }
}
