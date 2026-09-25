package gold.debug.windowstolinux.app.windows.update;

import java.util.Locale;
import java.util.Objects;

/**
 * Supported signed desktop package architectures. / 受支持的签名桌面包架构。
 */
public enum DesktopArchitectureType {
    /**
     * X 86 64 classification within desktop architecture type.
     * <p>Desktop架构类型中的X8664分类。
     */
    X86_64,
    /**
     * ARM 64 classification within desktop architecture type.
     * <p>Desktop架构类型中的ARM64分类。
     */
    ARM64;

    /**
     * Converts a bounded operating-system architecture name. / 转换有界操作系统架构名。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return constructed or resolved desktop architecture type / 构造或解析得到的Desktop架构类型
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static DesktopArchitectureType fromOperatingSystem(String value) {
        String normalized = Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "amd64", "x86_64" -> X86_64;
            case "aarch64", "arm64" -> ARM64;
            default -> throw new IllegalArgumentException("unsupported desktop architecture");
        };
    }
}
