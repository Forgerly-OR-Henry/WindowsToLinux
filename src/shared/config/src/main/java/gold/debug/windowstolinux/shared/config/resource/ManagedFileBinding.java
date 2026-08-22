package gold.debug.windowstolinux.shared.config.resource;

import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;

import java.util.Objects;

/** Stable managed identity for one reviewed logical persistent file tree. / 一个经审阅逻辑持久化文件树的稳定受管身份。 */
public record ManagedFileBinding(
        String bindingId,
        ComponentDataPath dataPath
) {
    /** Validates the identity and exact reviewed logical path. / 校验身份及精确审阅逻辑路径。 */
    public ManagedFileBinding {
        bindingId = ManagedDatabaseBinding.managedIdentifier(bindingId, "bindingId");
        dataPath = Objects.requireNonNull(dataPath, "dataPath");
    }
}
