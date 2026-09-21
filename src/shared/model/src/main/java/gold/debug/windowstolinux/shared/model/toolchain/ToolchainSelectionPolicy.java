package gold.debug.windowstolinux.shared.model.toolchain;

import java.util.List;
import java.util.Objects;

/**
 * Pure selection policy shared by preparation and bounded build retries. / 准备与有界构建重试共用的纯选择规则。
 */
public final class ToolchainSelectionPolicy {
    /**
     * Catalog.
     * <p>目录。
     */
    private final ToolchainSupportCatalog catalog;
    /**
     * Validates and binds the inputs required by toolchain selection policy.
     * <p>校验并绑定工具链选择策略所需输入。
     *
     * @param catalog catalog / 目录
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ToolchainSelectionPolicy(ToolchainSupportCatalog catalog) { this.catalog = Objects.requireNonNull(catalog); }

    /**
     * Resolves toolchain version.
     * <p>解析工具链版本。
     *
     * @param requirement requirement / 要求
     * @param branch branch / 分支
     * @param releases releases / 发布集合
     * @return toolchain version / 工具链版本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public ToolchainVersion resolve(ToolchainRequirement requirement, ToolchainSupportCatalog.Branch branch,
                                    List<ToolchainVersion> releases) {
        if (!catalog.candidates(requirement).contains(branch)) throw new IllegalArgumentException("branch is outside reviewed candidates");
        boolean direct = requirement.version().isPresent() && !requirement.version().orElseThrow().preview()
                && requirement.version().orElseThrow().branch().equals(branch.version());
        return releases.stream().filter(catalog::permits).filter(v -> v.ecosystem() == branch.ecosystem()
                && v.branch().equals(branch.version()))
                .filter(v -> !direct || requirement.accepts(v))
                .max(ToolchainVersion::compareTo).orElseThrow(() -> new IllegalArgumentException("no official release satisfies this candidate"));
    }

}
