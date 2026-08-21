package gold.debug.windowstolinux.shared.backup.manifest;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

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
        List<BackupComponent> components,
        String applicationHealthComponentId,
        BackupHealthCheck applicationHealthCheck,
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
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        if (components.isEmpty() || components.size() > 256) {
            throw new IllegalArgumentException("components must contain one to 256 reviewed values");
        }
        applicationHealthComponentId = Objects.requireNonNull(
                applicationHealthComponentId, "applicationHealthComponentId").trim();
        if (!applicationHealthComponentId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("applicationHealthComponentId is invalid");
        }
        applicationHealthCheck = Objects.requireNonNull(applicationHealthCheck, "applicationHealthCheck");
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
        validateComponents(releaseManifests, configurationSnapshots, serviceDefinitions,
                components, applicationHealthComponentId);
    }

    private static void validateComponents(
            List<String> releaseManifests,
            List<String> configurationSnapshots,
            List<String> serviceDefinitions,
            List<BackupComponent> components,
            String applicationHealthComponentId
    ) {
        Set<String> componentIds = new LinkedHashSet<>();
        Set<String> managedIds = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (BackupComponent component : components) {
            if (!componentIds.add(component.componentId()) || !managedIds.add(component.managedApplicationId())) {
                throw new IllegalArgumentException("component and managed application identities must be unique");
            }
            if (!seen.containsAll(component.dependsOn())) {
                throw new IllegalArgumentException("components must be stored in dependency-first order");
            }
            seen.add(component.componentId());
        }
        if (!componentIds.contains(applicationHealthComponentId)) {
            throw new IllegalArgumentException("application health must be owned by one declared component");
        }
        Set<String> componentReleases = components.stream().map(BackupComponent::releaseManifestPath)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> componentConfigurations = components.stream().map(BackupComponent::configurationSnapshotPath)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> componentDefinitions = components.stream().map(BackupComponent::serviceDefinitionPath)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!componentReleases.equals(new LinkedHashSet<>(releaseManifests))
                || !componentConfigurations.equals(new LinkedHashSet<>(configurationSnapshots))
                || !componentDefinitions.equals(new LinkedHashSet<>(serviceDefinitions))) {
            throw new IllegalArgumentException("component archive references must exactly cover the inventory definitions");
        }
    }
}
