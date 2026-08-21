package gold.debug.windowstolinux.app.windows.update;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/** Signed immutable metadata for one staged desktop package. / 单个已暂存桌面包的签名不可变元数据。 */
public record DesktopUpdateManifest(
        String releaseId,
        DesktopReleaseVersion version,
        DesktopArchitectureType architecture,
        long packageBytes,
        String packageSha256,
        Instant issuedAt,
        Instant expiresAt,
        String keyId,
        boolean emergencyRollback,
        String signatureBase64
) {
    /** Current signed metadata schema. / 当前签名元数据 schema。 */
    public static final int SCHEMA_VERSION = 1;
    private static final long MAXIMUM_PACKAGE_BYTES = 4L * 1024 * 1024 * 1024;

    /** Validates bounded metadata before cryptographic verification. / 在密码学验证前校验有界元数据。 */
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

    byte[] signedPayload() {
        String canonical = "schema=" + SCHEMA_VERSION + '\n'
                + "releaseId=" + releaseId + '\n'
                + "version=" + version + '\n'
                + "architecture=" + architecture + '\n'
                + "packageBytes=" + packageBytes + '\n'
                + "packageSha256=" + packageSha256 + '\n'
                + "issuedAt=" + issuedAt.getEpochSecond() + '\n'
                + "expiresAt=" + expiresAt.getEpochSecond() + '\n'
                + "keyId=" + keyId + '\n'
                + "emergencyRollback=" + emergencyRollback + '\n';
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private static String identifier(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String digest(String value) {
        value = Objects.requireNonNull(value, "packageSha256").trim();
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("packageSha256 is invalid");
        return value;
    }
}
