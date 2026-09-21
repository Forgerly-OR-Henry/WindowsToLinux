package gold.debug.windowstolinux.shared.backup.contract.validation;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Independent local validation evidence for one downloaded managed artifact. / 一个已下载受管制品的独立本地校验证据。
 *
 * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
 * @param format format / 格式
 * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
 * @param sha256 lower-case hexadecimal SHA-256 digest / 小写十六进制 SHA-256 摘要
 * @param entries the type-checked entries / 经类型检查的条目
 */
public record ManagedArtifactEvidence(
        Path path,
        ManagedArtifactFormatType format,
        long byteCount,
        String sha256,
        int entries
) {
    /**
     * Validates local evidence. / 校验本地证据。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param format format / 格式
     * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
     * @param sha256 lower-case hexadecimal SHA-256 digest / 小写十六进制 SHA-256 摘要
     * @param entries the type-checked entries / 经类型检查的条目
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedArtifactEvidence {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        format = Objects.requireNonNull(format, "format");
        sha256 = Objects.requireNonNull(sha256, "sha256");
        if (byteCount < 1 || entries < 1 || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("managed artifact evidence is invalid");
        }
    }
}
