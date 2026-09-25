package gold.debug.windowstolinux.shared.model.project.application;

import java.util.Objects;

/**
 * External input is read-only and excluded from application backup contents. / 外部输入只读且不进入应用内容备份。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param hostPath host path / 主机路径
 * @param accessPath access path / 访问路径
 */
public record ApplicationInput(String id, String hostPath, String accessPath) {
    /**
     * Validates and binds the inputs required by application input.
     * <p>校验并绑定应用输入所需输入。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param hostPath host path / 主机路径
     * @param accessPath access path / 访问路径
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ApplicationInput {
        if (!Objects.requireNonNull(id).matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("invalid input identifier");
        hostPath = absolute(hostPath);
        accessPath = absolute(accessPath);
        for (String forbidden : java.util.List.of("/proc", "/sys", "/dev", "/run/credentials",
                "/usr/local/lib/windowstolinux", "/var/lib/windowstolinux")) {
            if (hostPath.equals(forbidden) || hostPath.startsWith(forbidden + "/") || accessPath.equals(forbidden)
                    || accessPath.startsWith(forbidden + "/"))
                throw new IllegalArgumentException("external input overlaps runtime control resources");
        }
        for (String protectedRoot : java.util.List.of("/bin", "/sbin", "/lib", "/lib64", "/usr", "/etc", "/opt", "/var",
                "/root", "/run", "/tmp")) {
            if (accessPath.equals(protectedRoot) || accessPath.startsWith(protectedRoot + "/"))
                throw new IllegalArgumentException(
                        "external input target overlaps program, configuration or managed storage");
        }
    }

    /**
     * Checks absolute syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查绝对语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return absolute text / 绝对文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String absolute(String value) {
        Objects.requireNonNull(value);
        if (!value.startsWith("/") || value.equals("/") || value.length() > 512 || value.contains("//")
                || !value.matches("/[\\p{L}\\p{N}_./ -]+") || java.util.Arrays.stream(value.substring(1).split("/", -1))
                        .anyMatch(part -> part.isEmpty() || part.equals("..") || part.equals(".")))
            throw new IllegalArgumentException("external input requires a literal absolute path");
        return value;
    }
}
