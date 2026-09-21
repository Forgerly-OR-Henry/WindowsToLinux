package gold.debug.windowstolinux.app.windows.uninstall;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact jpackage, data and credential boundaries for one uninstall request. / 单次卸载请求的精确 jpackage、数据及凭据边界。
 *
 * @param decision decision / 决定
 * @param installRoot install root / 安装根目录
 * @param dataRoot data root / 数据根目录
 * @param credentialNamespace credential namespace / 凭据命名空间
 */
public record DesktopUninstallRequest(
        Optional<DesktopUninstallDecisionType> decision,
        Path installRoot,
        Path dataRoot,
        String credentialNamespace
) {
    /**
     * MAXIMUM PATH CHARACTERS.
     * <p>最大路径字符集合。
     */
    private static final int MAXIMUM_PATH_CHARACTERS = 4096;

    /**
     * Validates fixed application-owned local paths without choosing a default decision. / 校验固定应用本地路径且不选择默认决定。
     *
     * @param decision decision / 决定
     * @param installRoot install root / 安装根目录
     * @param dataRoot data root / 数据根目录
     * @param credentialNamespace credential namespace / 凭据命名空间
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopUninstallRequest {
        decision = Objects.requireNonNull(decision, "decision");
        installRoot = normalized(installRoot, "installRoot");
        dataRoot = normalized(dataRoot, "dataRoot");
        if (installRoot.getParent() == null || !dataRoot.equals(installRoot.resolve("data"))) {
            throw new IllegalArgumentException("desktop data root must be the fixed data child of the install root");
        }
        credentialNamespace = Objects.requireNonNull(credentialNamespace, "credentialNamespace").trim();
        if (!credentialNamespace.equals("WindowsToLinux/*")) {
            throw new IllegalArgumentException("credential namespace is outside the WindowsToLinux boundary");
        }
    }

    /**
     * Normalizes the supplied contents according to the owning contract.
     * <p>按所属契约规范化所提供内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return constructed or resolved path / 构造或解析得到的路径
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static Path normalized(Path value, String field) {
        Path path = Objects.requireNonNull(value, field).toAbsolutePath().normalize();
        String pathText = path.toString();
        if (path.getParent() == null || pathText.length() > MAXIMUM_PATH_CHARACTERS
                || pathText.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is outside the supported boundary");
        }
        return path;
    }
}
