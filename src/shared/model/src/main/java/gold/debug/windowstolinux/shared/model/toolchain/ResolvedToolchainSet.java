package gold.debug.windowstolinux.shared.model.toolchain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable per-release tool identities, never reselected by a later catalog revision. / 发布级不可变工具身份，不随目录修订重新选择。 */
public record ResolvedToolchainSet(String catalogRevision, List<Selection> selections) {
    public enum OriginType { SYSTEM, MANAGED }
    public record Selection(ToolchainRequirement requirement, ToolchainVersion version, String directory,
                            OriginType origin, String source, String sha256) {
        public Selection {
            Objects.requireNonNull(requirement); Objects.requireNonNull(version); Objects.requireNonNull(origin);
            if (requirement.ecosystem() != version.ecosystem() || version.preview())
                throw new IllegalArgumentException("selected tool must be a matching stable ecosystem");
            if (directory == null || !directory.matches("/(?:[A-Za-z0-9_+.-]+/)*[A-Za-z0-9_+.-]+")
                    || directory.contains("/../") || directory.contains("/./") || directory.endsWith("/.."))
                throw new IllegalArgumentException("invalid toolchain directory");
            if (source == null || source.isBlank() || source.length() > 2048 || source.chars().anyMatch(c -> c < 32))
                throw new IllegalArgumentException("invalid toolchain source evidence");
            if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("toolchain digest required");
        }
    }
    public ResolvedToolchainSet {
        if (catalogRevision == null || !catalogRevision.matches("[A-Za-z0-9._-]{1,96}"))
            throw new IllegalArgumentException("invalid catalog revision");
        selections = List.copyOf(selections);
        if (selections.size() > 32 || selections.stream().map(s -> s.requirement().ecosystem() + ":" + s.requirement().purpose())
                .distinct().count() != selections.size()) throw new IllegalArgumentException("duplicate toolchain role");
    }
    public Optional<Selection> find(ToolchainEcosystemType ecosystem, ToolchainRequirement.PurposeType purpose) {
        return selections.stream().filter(s -> s.requirement().ecosystem() == ecosystem && s.requirement().purpose() == purpose).findFirst();
    }
}
