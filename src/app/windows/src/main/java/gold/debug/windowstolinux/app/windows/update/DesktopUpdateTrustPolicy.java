package gold.debug.windowstolinux.app.windows.update;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Pinned release trust root and local update policy. / 固定发布信任根及本地更新策略。 */
public record DesktopUpdateTrustPolicy(
        DesktopReleaseVersion currentVersion,
        DesktopArchitectureType currentArchitecture,
        String trustedKeyId,
        PublicKey trustedPublicKey,
        Set<String> revokedReleaseIds,
        Instant verificationTime,
        boolean emergencyRollbackApproved
) {
    /** Validates a pinned Ed25519 key and immutable revocation set. / 校验固定 Ed25519 密钥及不可变撤销集合。 */
    public DesktopUpdateTrustPolicy {
        currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        currentArchitecture = Objects.requireNonNull(currentArchitecture, "currentArchitecture");
        trustedKeyId = identifier(trustedKeyId, "trustedKeyId");
        trustedPublicKey = Objects.requireNonNull(trustedPublicKey, "trustedPublicKey");
        if (!trustedPublicKey.getAlgorithm().equalsIgnoreCase("EdDSA")
                && !trustedPublicKey.getAlgorithm().equalsIgnoreCase("Ed25519")) {
            throw new IllegalArgumentException("desktop update trust root must be Ed25519");
        }
        revokedReleaseIds = Set.copyOf(Objects.requireNonNull(revokedReleaseIds, "revokedReleaseIds"));
        revokedReleaseIds.forEach(value -> identifier(value, "revokedReleaseId"));
        verificationTime = Objects.requireNonNull(verificationTime, "verificationTime");
    }

    private static String identifier(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
