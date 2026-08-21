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
    /** Freezes verified package evidence. / 冻结已验证软件包证据。 */
    public DesktopUpdateVerification {
        packageFile = Objects.requireNonNull(packageFile, "packageFile").toAbsolutePath().normalize();
        releaseId = Objects.requireNonNull(releaseId, "releaseId");
        version = Objects.requireNonNull(version, "version");
        architecture = Objects.requireNonNull(architecture, "architecture");
        if (packageBytes < 1) throw new IllegalArgumentException("packageBytes must be positive");
        packageSha256 = Objects.requireNonNull(packageSha256, "packageSha256");
        verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty()) throw new IllegalArgumentException("update verification evidence is incomplete");
    }
}
