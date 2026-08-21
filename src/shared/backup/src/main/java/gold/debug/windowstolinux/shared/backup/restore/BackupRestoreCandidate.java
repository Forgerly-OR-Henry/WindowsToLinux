package gold.debug.windowstolinux.shared.backup.restore;

import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;

import java.nio.file.Path;
import java.util.Objects;

/** Fully extracted but not yet activated restore candidate. / 已完整提取但尚未激活的恢复候选。 */
public record BackupRestoreCandidate(Path root, BackupManifest manifest, long extractedBytes) {
    /** Validates isolated candidate evidence. / 校验隔离候选证据。 */
    public BackupRestoreCandidate {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        manifest = Objects.requireNonNull(manifest, "manifest");
        if (extractedBytes < 0) throw new IllegalArgumentException("extractedBytes must not be negative");
    }
}
