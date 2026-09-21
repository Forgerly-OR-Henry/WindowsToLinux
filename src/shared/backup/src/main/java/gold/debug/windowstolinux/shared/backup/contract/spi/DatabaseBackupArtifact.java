package gold.debug.windowstolinux.shared.backup.contract.spi;

import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabase;

import java.util.List;
import java.util.Objects;

/**
 * Opaque remote export artifact with exact integrity and consistency evidence. / 带精确完整性与一致性证据的不透明远程导出物。
 *
 * @param artifactId artifact id / 制品标识
 * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
 * @param sha256 lower-case hexadecimal SHA-256 digest / 小写十六进制 SHA-256 摘要
 * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record DatabaseBackupArtifact(
        String artifactId,
        long byteCount,
        String sha256,
        BackupDatabase database,
        List<String> evidence
) {
    /**
     * Validates an export artifact without exposing a remote path. / 在不暴露远程路径的情况下校验导出物。
     *
     * @param artifactId artifact id / 制品标识
     * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
     * @param sha256 lower-case hexadecimal SHA-256 digest / 小写十六进制 SHA-256 摘要
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DatabaseBackupArtifact {
        artifactId = DatabaseContractRules.identifier(artifactId, "artifactId");
        if (byteCount < 1) throw new IllegalArgumentException("database artifact must not be empty");
        sha256 = Objects.requireNonNull(sha256, "sha256");
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("database artifact hash is invalid");
        database = Objects.requireNonNull(database, "database");
        evidence = DatabaseContractRules.evidence(evidence);
    }
}
