package gold.debug.windowstolinux.app.windows.update;

import java.util.Locale;
import java.util.Objects;

/** Supported signed desktop package architectures. / 受支持的签名桌面包架构。 */
public enum DesktopArchitectureType {
    X86_64,
    ARM64;

    /** Converts a bounded operating-system architecture name. / 转换有界操作系统架构名。 */
    public static DesktopArchitectureType fromOperatingSystem(String value) {
        String normalized = Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "amd64", "x86_64" -> X86_64;
            case "aarch64", "arm64" -> ARM64;
            default -> throw new IllegalArgumentException("unsupported desktop architecture");
        };
    }
}
