package gold.debug.windowstolinux.shared.git;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * A parsed Git remote that never embeds a credential in its location.
 *
 * <p>解析后的 Git 远端；其位置绝不嵌入凭据。
 *
 * @param location the remote URI / 远端 URI
 */
public record GitRemote(URI location) {
    /**
     * Creates a {@code GitRemote} instance.
     *
     * <p>创建 {@code GitRemote} 实例。
     */
    public GitRemote {
        location = Objects.requireNonNull(location, "location").normalize();
        String scheme = location.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("ssh")
                || scheme.equalsIgnoreCase("file"))) {
            throw new IllegalArgumentException("Git remotes must use https, ssh, or file URIs");
        }
        if (location.getRawUserInfo() != null || location.getRawQuery() != null || location.getRawFragment() != null) {
            throw new IllegalArgumentException("Git remotes must not carry user info, queries, or fragments");
        }
        if (!scheme.equalsIgnoreCase("file") && (location.getHost() == null || location.getHost().isBlank())) {
            throw new IllegalArgumentException("network Git remotes must include a host");
        }
        if (location.toString().length() > 2048) {
            throw new IllegalArgumentException("Git remote URI is too long");
        }
    }

    /**
     * Parses a remote URI without accepting scp-like or credential-bearing syntax.
     *
     * <p>解析远端 URI，不接受 scp 风格或携带凭据的语法。
     *
     * @param value the remote URI text / 远端 URI 文本
     * @return the parsed remote / 解析后的远端
     */
    public static GitRemote parse(String value) {
        try {
            return new GitRemote(new URI(Objects.requireNonNull(value, "value").trim()));
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Git remote must be a valid URI", exception);
        }
    }

    /**
     * Returns the normalized host for allow-list checks.
     *
     * <p>返回用于允许列表检查的规范化主机名。
     *
     * @return the optional normalized host / 可选的规范化主机名
     */
    public java.util.Optional<String> host() {
        return java.util.Optional.ofNullable(location.getHost()).map(host -> host.toLowerCase(Locale.ROOT));
    }
}
