package gold.debug.windowstolinux.shared.backup.manifest;

import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Objects;

/**
 * Portable copy of one platform-managed container volume. / 单个平台受管容器卷的可移植副本。
 *
 * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
 * @param containerPath container path / 容器路径
 * @param readOnly read only / 读取仅
 */
public record BackupManagedVolume(String name, String containerPath, boolean readOnly) {
    /**
     * Validates the value through the canonical deployment model. / 通过规范部署模型校验该值。
     *
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param containerPath container path / 容器路径
     * @param readOnly read only / 读取仅
     */
    public BackupManagedVolume {
        DeploymentRuntimeSpecification.ManagedVolume checked =
                new DeploymentRuntimeSpecification.ManagedVolume(name, containerPath, readOnly);
        name = checked.name();
        containerPath = checked.containerPath();
    }

    /**
     * Converts to the canonical managed-volume model. / 转换为规范受管卷模型。
     *
     * @return constructed or resolved managed volume / 构造或解析得到的受管卷
     */
    public DeploymentRuntimeSpecification.ManagedVolume toManagedVolume() {
        return new DeploymentRuntimeSpecification.ManagedVolume(name, containerPath, readOnly);
    }

    /**
     * Copies one canonical managed volume. / 复制一个规范受管卷。
     *
     * @param volume volume / 卷
     * @return constructed or resolved backup managed volume / 构造或解析得到的备份受管卷
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static BackupManagedVolume from(DeploymentRuntimeSpecification.ManagedVolume volume) {
        Objects.requireNonNull(volume, "volume");
        return new BackupManagedVolume(volume.name(), volume.containerPath(), volume.readOnly());
    }
}
