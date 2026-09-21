package gold.debug.windowstolinux.shared.backup.manifest;

import java.util.Locale;
import java.util.Objects;

/**
 * Expected immutable member with exact size and digest. / 带精确大小与摘要的预期不可变成员。
 *
 * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
 * @param size size / 大小
 * @param sha256 lower-case hexadecimal SHA-256 digest / 小写十六进制 SHA-256 摘要
 * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
 */
public record BackupMember(String path, long size, String sha256, BackupMemberKind kind) {
    /**
     * Validates the canonical member identity. / 校验规范成员身份。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param size size / 大小
     * @param sha256 lower-case hexadecimal SHA-256 digest / 小写十六进制 SHA-256 摘要
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupMember {
        path = BackupManifestRules.archivePath(path);
        if (size < 0) throw new IllegalArgumentException("member size must not be negative");
        sha256 = Objects.requireNonNull(sha256, "sha256").toLowerCase(Locale.ROOT);
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("member hash must be canonical SHA-256");
        kind = Objects.requireNonNull(kind, "kind");
        String requiredPrefix = switch (kind) {
            case RELEASE -> "releases/";
            case CONFIGURATION -> "config/";
            case PERSISTENT_CONTENT -> "data/";
            case DATABASE -> "database/";
            case RUNTIME -> "runtime/";
            case ENCRYPTED_SECRETS -> "secrets.enc";
        };
        if (kind == BackupMemberKind.ENCRYPTED_SECRETS ? !path.equals(requiredPrefix) : !path.startsWith(requiredPrefix)) {
            throw new IllegalArgumentException("member path does not match its declared kind");
        }
    }
}
