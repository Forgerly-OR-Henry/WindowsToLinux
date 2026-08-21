package gold.debug.windowstolinux.shared.backup.manifest;

import java.util.List;
import java.util.Objects;

/** Complete structured inventory needed to recreate one managed application. / 重建一个受管应用所需的完整结构化清单。 */
public record BackupInventory(
        List<String> releaseManifests,
        List<String> configurationSnapshots,
        List<String> secretReferences,
        List<String> persistentFiles,
        List<String> persistentVolumes,
        BackupDatabase database,
        BackupIdentity identity,
        List<String> serviceDefinitions,
        BackupRuntime runtime,
        List<String> recoveryRequirements
) {
    /** Freezes a complete, bounded and duplicate-free inventory. / 冻结完整、有界且无重复的清单。 */
    public BackupInventory {
        releaseManifests = BackupManifestRules.distinctTexts(releaseManifests, "releaseManifests", 256, 512);
        configurationSnapshots = BackupManifestRules.distinctTexts(
                configurationSnapshots, "configurationSnapshots", 256, 512);
        secretReferences = BackupManifestRules.distinctTexts(secretReferences, "secretReferences", 256, 256);
        persistentFiles = BackupManifestRules.distinctTexts(persistentFiles, "persistentFiles", 4096, 1024);
        persistentVolumes = BackupManifestRules.distinctTexts(persistentVolumes, "persistentVolumes", 1024, 1024);
        database = Objects.requireNonNull(database, "database");
        identity = Objects.requireNonNull(identity, "identity");
        serviceDefinitions = BackupManifestRules.distinctTexts(serviceDefinitions, "serviceDefinitions", 256, 1024);
        runtime = Objects.requireNonNull(runtime, "runtime");
        recoveryRequirements = BackupManifestRules.distinctTexts(
                recoveryRequirements, "recoveryRequirements", 128, 512);
        if (releaseManifests.isEmpty()) {
            throw new IllegalArgumentException("at least one release manifest is required");
        }
        if (configurationSnapshots.isEmpty()) {
            throw new IllegalArgumentException("at least one configuration snapshot is required");
        }
        if (serviceDefinitions.isEmpty()) {
            throw new IllegalArgumentException("at least one service or container definition is required");
        }
    }
}
