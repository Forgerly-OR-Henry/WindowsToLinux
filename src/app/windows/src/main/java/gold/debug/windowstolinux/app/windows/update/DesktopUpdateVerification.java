package gold.debug.windowstolinux.app.windows.update;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Complete package integrity, trust, version and architecture evidence. / 完整的软件包完整性、信任、版本及架构证据。
 *
 * @param packageFile package file / 软件包文件
 * @param releaseId release id / 发布标识
 * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
 * @param architecture observed machine architecture / 观测到的机器架构
 * @param packageBytes package bytes / 软件包字节
 * @param packageSha256 package sha 256 / 软件包SHA256
 * @param verifiedAt verified at / 已验证时刻
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record DesktopUpdateVerification(
        Path packageFile,
        String releaseId,
        DesktopReleaseVersion version,
        DesktopArchitectureType architecture,
        long packageBytes,
        String packageSha256,
        Instant verifiedAt,
        List<String> evidence
) {
    /**
     * MAXIMUM PATH CHARACTERS.
     * <p>最大路径字符集合。
     */
    private static final int MAXIMUM_PATH_CHARACTERS = 4096;

    /**
     * Freezes verified package evidence. / 冻结已验证软件包证据。
     *
     * @param packageFile package file / 软件包文件
     * @param releaseId release id / 发布标识
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param architecture observed machine architecture / 观测到的机器架构
     * @param packageBytes package bytes / 软件包字节
     * @param packageSha256 package sha 256 / 软件包SHA256
     * @param verifiedAt verified at / 已验证时刻
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopUpdateVerification {
        packageFile = Objects.requireNonNull(packageFile, "packageFile").toAbsolutePath().normalize();
        String packagePath = packageFile.toString();
        if (packageFile.getParent() == null || packagePath.length() > MAXIMUM_PATH_CHARACTERS
                || packagePath.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("packageFile is outside the supported boundary");
        }
        releaseId = identifier(releaseId, "releaseId");
        version = Objects.requireNonNull(version, "version");
        architecture = Objects.requireNonNull(architecture, "architecture");
        if (packageBytes < 1) throw new IllegalArgumentException("packageBytes must be positive");
        packageSha256 = Objects.requireNonNull(packageSha256, "packageSha256").trim();
        if (!packageSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("packageSha256 is invalid");
        }
        verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.isEmpty() || evidence.size() > 64) {
            throw new IllegalArgumentException("update verification evidence is incomplete");
        }
        List<String> validatedEvidence = evidence.stream().map(value -> {
            String item = Objects.requireNonNull(value, "evidence item").trim();
            if (item.isEmpty() || item.length() > 512 || item.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("update verification evidence item is invalid");
            }
            return item;
        }).distinct().toList();
        if (validatedEvidence.size() != evidence.size()) {
            throw new IllegalArgumentException("update verification evidence has duplicates");
        }
        evidence = validatedEvidence;
    }

    /**
     * Validates an identifier against the bounded syntax of the owning contract.
     * <p>按所属契约的有界语法验证标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return identifier text / 标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String identifier(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
