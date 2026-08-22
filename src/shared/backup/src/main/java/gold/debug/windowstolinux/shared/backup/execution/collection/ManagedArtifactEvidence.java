package gold.debug.windowstolinux.shared.backup.execution.collection;

import java.nio.file.Path;
import java.util.Objects;

/** Independent local validation evidence for one downloaded managed artifact. / 一个已下载受管制品的独立本地校验证据。 */
public record ManagedArtifactEvidence(
        Path path,
        ManagedArtifactFormatType format,
        long byteCount,
        String sha256,
        int entries
) {
    /** Validates local evidence. / 校验本地证据。 */
    public ManagedArtifactEvidence {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        format = Objects.requireNonNull(format, "format");
        sha256 = Objects.requireNonNull(sha256, "sha256");
        if (byteCount < 1 || entries < 1 || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("managed artifact evidence is invalid");
        }
    }
}
