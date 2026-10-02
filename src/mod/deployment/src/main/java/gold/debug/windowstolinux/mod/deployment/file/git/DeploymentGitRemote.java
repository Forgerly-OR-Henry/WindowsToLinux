package gold.debug.windowstolinux.mod.deployment.file.git;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * A parsed Git remote that never embeds a credential in its location.
 *
 *  <p>解析后的 Git 远端；其位置绝不嵌入凭据。
 *
 * @param location the remote URI / 远端 URI
 */
public record DeploymentGitRemote(URI location) {
    /**
     * Validates and binds the inputs required by git remote.
     * <p>校验并绑定Git远端所需输入。
     *
     * @param location the remote URI / 远端 URI
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentGitRemote {
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
        if (scheme.equalsIgnoreCase("file")
            && (location.getRawAuthority() != null && !location.getRawAuthority().isEmpty()
            || location.getPath() == null || !location.getPath().startsWith("/"))) {
            throw new IllegalArgumentException("file Git remotes must use an absolute local path");
        }
        if (location.toString().length() > 2048) {
            throw new IllegalArgumentException("Git remote URI is too long");
        }
    }

    /**
     * Parses a remote URI without accepting scp-like or credential-bearing syntax.
     *
     *  <p>解析远端 URI，不接受 scp 风格或携带凭据的语法。
     *
     * @param value the remote URI text / 远端 URI 文本
     * @return the parsed remote / 解析后的远端
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static DeploymentGitRemote parse(String value) {
        try {
            return new DeploymentGitRemote(new URI(Objects.requireNonNull(value, "value").trim()));
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Git remote must be a valid URI", exception);
        }
    }

    /**
     * Returns the normalized host for allow-list checks.
     *
     *  <p>返回用于允许列表检查的规范化主机名。
     *
     * @return the optional normalized host / 可选的规范化主机名
     */
    public java.util.Optional<String> host() {
        return java.util.Optional.ofNullable(location.getHost()).map(host -> host.toLowerCase(Locale.ROOT));
    }
}
