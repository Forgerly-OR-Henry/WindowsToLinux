package gold.debug.windowstolinux.app.windows.update;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * Signed immutable metadata for one staged desktop package. / 单个已暂存桌面包的签名不可变元数据。
 *
 * @param releaseId release id / 发布标识
 * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
 * @param architecture observed machine architecture / 观测到的机器架构
 * @param packageBytes package bytes / 软件包字节
 * @param packageSha256 package sha 256 / 软件包SHA256
 * @param issuedAt issued at / 签发时刻
 * @param expiresAt expires at / 到期时刻
 * @param keyId key id / 键标识
 * @param emergencyRollback emergency rollback / 紧急回滚
 * @param signatureBase64 signature base 64 / 签名基础64
 */
public record DesktopUpdateManifest(String releaseId, DesktopReleaseVersion version,
        DesktopArchitectureType architecture, long packageBytes, String packageSha256, Instant issuedAt,
        Instant expiresAt, String keyId, boolean emergencyRollback, String signatureBase64) {
    /**
     * Current signed metadata schema. / 当前签名元数据 schema。
     */
    public static final int SCHEMA_VERSION = 1;

    /**
     * MAXIMUM PACKAGE BYTES.
     * <p>最大软件包字节。
     */
    private static final long MAXIMUM_PACKAGE_BYTES = 4L * 1024 * 1024 * 1024;

    /**
     * Validates bounded metadata before cryptographic verification. / 在密码学验证前校验有界元数据。
     *
     * @param releaseId release id / 发布标识
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param architecture observed machine architecture / 观测到的机器架构
     * @param packageBytes package bytes / 软件包字节
     * @param packageSha256 package sha 256 / 软件包SHA256
     * @param issuedAt issued at / 签发时刻
     * @param expiresAt expires at / 到期时刻
     * @param keyId key id / 键标识
     * @param emergencyRollback emergency rollback / 紧急回滚
     * @param signatureBase64 signature base 64 / 签名基础64
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopUpdateManifest {
        releaseId = identifier(releaseId, "releaseId");
        version = Objects.requireNonNull(version, "version");
        architecture = Objects.requireNonNull(architecture, "architecture");
        if (packageBytes < 1 || packageBytes > MAXIMUM_PACKAGE_BYTES) {
            throw new IllegalArgumentException("desktop package size is outside the supported boundary");
        }
        packageSha256 = digest(packageSha256);
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt) || Duration.between(issuedAt, expiresAt).compareTo(Duration.ofDays(90)) > 0) {
            throw new IllegalArgumentException("desktop update validity window is invalid");
        }
        keyId = identifier(keyId, "keyId");
        signatureBase64 = Objects.requireNonNull(signatureBase64, "signatureBase64").trim();
        try {
            if (Base64.getDecoder().decode(signatureBase64).length != 64) {
                throw new IllegalArgumentException("Ed25519 update signature must contain 64 bytes");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("desktop update signature is not canonical Base64", exception);
        }
    }

    /**
     * Returns signed payload.
     * <p>返回已签名载荷。
     *
     * @return signed payload / 已签名载荷
     */
    byte[] signedPayload() {
        String canonical = "schema=" + SCHEMA_VERSION + '\n' + "releaseId=" + releaseId + '\n' + "version=" + version
                + '\n' + "architecture=" + architecture + '\n' + "packageBytes=" + packageBytes + '\n'
                + "packageSha256=" + packageSha256 + '\n' + "issuedAt=" + issuedAt.getEpochSecond() + '\n'
                + "expiresAt=" + expiresAt.getEpochSecond() + '\n' + "keyId=" + keyId + '\n' + "emergencyRollback="
                + emergencyRollback + '\n';
        return canonical.getBytes(StandardCharsets.UTF_8);
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

    /**
     * Computes or retrieves content identity for independent evidence checks.
     * <p>计算或取得用于独立证据检查的内容身份。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return or retrieves content identity for independent evidence checks / 或取得用于独立证据检查的内容身份
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String digest(String value) {
        value = Objects.requireNonNull(value, "packageSha256").trim();
        if (!value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("packageSha256 is invalid");
        return value;
    }
}
