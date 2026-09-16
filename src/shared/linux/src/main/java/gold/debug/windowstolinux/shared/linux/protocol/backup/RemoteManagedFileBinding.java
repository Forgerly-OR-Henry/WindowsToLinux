package gold.debug.windowstolinux.shared.linux.protocol.backup;

import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import java.util.Objects;

/** Exact reviewed file identity and logical data path for publication. / 发布所需的精确审阅文件身份与逻辑数据路径。 */
public record RemoteManagedFileBinding(String bindingId, ComponentDataPath dataPath) {
    /** Rejects unbounded identities and absent paths. / 拒绝无界身份和缺失路径。 */
    public RemoteManagedFileBinding {
        Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(dataPath, "dataPath");
        if (!bindingId.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException("invalid file binding identity");
    }
}
