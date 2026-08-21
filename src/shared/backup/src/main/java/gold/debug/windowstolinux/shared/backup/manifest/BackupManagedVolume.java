package gold.debug.windowstolinux.shared.backup.manifest;

import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Objects;

/** Portable copy of one platform-managed container volume. / 单个平台受管容器卷的可移植副本。 */
public record BackupManagedVolume(String name, String containerPath, boolean readOnly) {
    /** Validates the value through the canonical deployment model. / 通过规范部署模型校验该值。 */
    public BackupManagedVolume {
        DeploymentRuntimeSpecification.ManagedVolume checked =
                new DeploymentRuntimeSpecification.ManagedVolume(name, containerPath, readOnly);
        name = checked.name();
        containerPath = checked.containerPath();
    }

    /** Converts to the canonical managed-volume model. / 转换为规范受管卷模型。 */
    public DeploymentRuntimeSpecification.ManagedVolume toManagedVolume() {
        return new DeploymentRuntimeSpecification.ManagedVolume(name, containerPath, readOnly);
    }

    /** Copies one canonical managed volume. / 复制一个规范受管卷。 */
    public static BackupManagedVolume from(DeploymentRuntimeSpecification.ManagedVolume volume) {
        Objects.requireNonNull(volume, "volume");
        return new BackupManagedVolume(volume.name(), volume.containerPath(), volume.readOnly());
    }
}
