package gold.debug.windowstolinux.appapi.file.material;

import java.util.Objects;
import java.util.Optional;

/** Read-only original custody facts; a missing reference never authorizes consumption. / 只读原接管事实，无引用从不授权消费。
 * @param state exact original lifecycle / 精确原生命周期
 * @param reference complete original published metadata, absent for unpublished custody / 完整原发布元数据，未发布接管为空 */
public record MaterialInspection(MaterialState state, Optional<MaterialRef> reference) {
    /** Rejects impossible state and publication combinations. / 拒绝不可能的状态与发布组合。
     * @param state original lifecycle / 原生命周期
     * @param reference original complete metadata / 原完整元数据 */
    public MaterialInspection {
        Objects.requireNonNull(state);
        Objects.requireNonNull(reference);
        if ((state == MaterialState.ABSENT || state == MaterialState.CAPTURING) && reference.isPresent()
            || state == MaterialState.PUBLISHED && reference.isEmpty())
            throw new IllegalArgumentException("Material inspection publication differs from its state");
    }
}
