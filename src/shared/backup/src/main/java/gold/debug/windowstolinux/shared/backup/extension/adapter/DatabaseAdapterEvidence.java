package gold.debug.windowstolinux.shared.backup.extension.adapter;

import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupArtifact;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseCompatibilityEvidence;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.manifest.BackupConsistencyMode;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;

final class DatabaseAdapterEvidence {
    private DatabaseAdapterEvidence() {
    }

    static void requireReady(DatabaseCompatibilityEvidence evidence, BackupDatabaseType type) throws BackupException {
        if (evidence.type() != type || !evidence.toolAvailable() || !evidence.engineVersionCompatible()) {
            throw BackupException.create(BackupFailureType.DATABASE_PREFLIGHT_FAILED,
                    "database type, tool availability or engine compatibility preflight failed");
        }
    }

    static DatabaseBackupArtifact verifyArtifact(
            DatabaseBackupArtifact artifact, BackupDatabaseType type, BackupConsistencyMode mode,
            DatabaseCompatibilityEvidence evidence) throws BackupException {
        if (artifact.database().type() != type || artifact.database().consistencyMode() != mode
                || !artifact.database().engineVersion().equals(evidence.engineVersion())
                || !artifact.database().toolVersion().equals(evidence.toolVersion())) {
            throw BackupException.create(BackupFailureType.DATABASE_EVIDENCE_INVALID,
                    "database export evidence differs from its completed preflight");
        }
        return artifact;
    }
}
