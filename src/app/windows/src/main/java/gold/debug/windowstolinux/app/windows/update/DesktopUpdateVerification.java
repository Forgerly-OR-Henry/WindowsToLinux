package gold.debug.windowstolinux.app.windows.update;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Complete package integrity, trust, version and architecture evidence. / 完整的软件包完整性、信任、版本及架构证据。 */
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
    private static final int MAXIMUM_PATH_CHARACTERS = 4096;

    /** Freezes verified package evidence. / 冻结已验证软件包证据。 */
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

    private static String identifier(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
