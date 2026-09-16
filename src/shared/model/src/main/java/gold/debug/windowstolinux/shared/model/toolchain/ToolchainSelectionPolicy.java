package gold.debug.windowstolinux.shared.model.toolchain;

import java.util.List;
import java.util.Objects;

/** Pure selection policy shared by preparation and bounded build retries. / 准备与有界构建重试共用的纯选择规则。 */
public final class ToolchainSelectionPolicy {
    private final ToolchainSupportCatalog catalog;
    public ToolchainSelectionPolicy(ToolchainSupportCatalog catalog) { this.catalog = Objects.requireNonNull(catalog); }

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
