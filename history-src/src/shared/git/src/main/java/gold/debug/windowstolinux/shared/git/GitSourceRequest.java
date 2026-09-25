package gold.debug.windowstolinux.shared.git;

import java.util.Objects;
import java.util.Set;

import gold.debug.windowstolinux.shared.git.GitReference;
import gold.debug.windowstolinux.shared.git.GitRemote;

/**
 * Bounded input for a read-only Git analysis snapshot; credentials remain external to this value.
 *
 *  <p>只读 Git 分析快照的有界输入；凭据始终位于此值之外。
 *
 * @param remote the credential-free remote / 不含凭据的远端
 * @param reference the selected reference / 选定引用
 * @param allowedHosts the normalized allowed network hosts / 规范化的允许网络主机
 * @param maximumArchiveBytes the maximum generated source archive size / 生成源码归档的最大大小
 * @param permitLocalFileRemote whether test or controlled local remotes are permitted / 是否允许测试或受控本地远端
 */
public record GitSourceRequest(GitRemote remote, GitReference reference, Set<String> allowedHosts,
        long maximumArchiveBytes, boolean permitLocalFileRemote) {
    /**
     * Validates and binds the inputs required by git source request.
     * <p>校验并绑定Git源码请求所需输入。
     *
     * @param remote the credential-free remote / 不含凭据的远端
     * @param reference the selected reference / 选定引用
     * @param allowedHosts the normalized allowed network hosts / 规范化的允许网络主机
     * @param maximumArchiveBytes the maximum generated source archive size / 生成源码归档的最大大小
     * @param permitLocalFileRemote whether test or controlled local remotes are permitted / 是否允许测试或受控本地远端
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public GitSourceRequest {
        remote = Objects.requireNonNull(remote, "remote");
        reference = Objects.requireNonNull(reference, "reference");
        allowedHosts = normalizeAllowedHosts(allowedHosts);
        if (maximumArchiveBytes < 1 || maximumArchiveBytes > 4L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("maximumArchiveBytes must be between 1 byte and 4 GiB");
        }
        if (remote.location().getScheme().equalsIgnoreCase("file") && !permitLocalFileRemote) {
            throw new IllegalArgumentException("file Git remotes require explicit controlled-local permission");
        }
        java.util.Optional<String> remoteHost = remote.host();
        if (remoteHost.isPresent() && !allowedHosts.contains(remoteHost.get())) {
            throw new IllegalArgumentException("Git remote host is not in the explicit allow list");
        }
    }

    /**
     * Normalizes the normalized allowed network hosts.
     * <p>规范化规范化的允许网络主机。
     *
     * @param allowedHosts the normalized allowed network hosts / 规范化的允许网络主机
     * @return constructed or resolved set / 构造或解析得到的集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static Set<String> normalizeAllowedHosts(Set<String> allowedHosts) {
        return Set.copyOf(Objects.requireNonNull(allowedHosts, "allowedHosts").stream().map(
                host -> Objects.requireNonNull(host, "allowedHosts entry").trim().toLowerCase(java.util.Locale.ROOT))
                .peek(host -> {
                    if (!host.matches("[a-z0-9.-]{1,253}")) {
                        throw new IllegalArgumentException("allowedHosts must contain normalized host names");
                    }
                }).toList());
    }
}
