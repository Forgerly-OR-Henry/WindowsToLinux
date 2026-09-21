package gold.debug.windowstolinux.shared.analyze.component;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import java.nio.file.Path;
import java.util.List;

/**
 * Declared deployable roots and possible types; ambiguity is preserved for input resolution. / 声明可部署根目录及可能类型，保留歧义供输入解析处理。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param relativeRoot relative root / 相对根目录
 * @param types types / 类型集合
 */
public record DiscoveredProjectComponent(String id, Path relativeRoot, List<DeploymentProjectType> types) {
    /**
     * Freezes candidate types. / 固定候选类型。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param relativeRoot relative root / 相对根目录
     * @param types types / 类型集合
     */
    public DiscoveredProjectComponent { types = List.copyOf(types); }
}
