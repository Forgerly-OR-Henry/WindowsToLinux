package gold.debug.windowstolinux.shared.model.toolchain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** The single, injectable build-release allowlist; release discovery supplies patch versions. / 唯一可注入构建分支目录，补丁由官方发布发现提供。 */
public final class ToolchainSupportCatalog {
    public enum ReleaseType { LTS, STABLE, LANGUAGE_STANDARD }
    public enum MaintenanceStatus { MAINTAINED, HISTORICAL, STANDARD }
    public enum InstallationType { TEMURIN, NODE_ARCHIVE, CPYTHON_SOURCE, DOTNET_SDK, KOTLIN_ARCHIVE,
        GO_ARCHIVE, RUST_STANDALONE, PHP_SOURCE, RUBY_SOURCE, SYSTEM_COMPILER }

    public record Branch(ToolchainEcosystemType ecosystem, String version, ReleaseType releaseType,
                         MaintenanceStatus maintenance, InstallationType installation) {
        public Branch {
            Objects.requireNonNull(ecosystem); Objects.requireNonNull(releaseType);
            Objects.requireNonNull(maintenance); Objects.requireNonNull(installation);
            ToolchainVersion parsed = ToolchainVersion.parse(ecosystem, version).orElseThrow();
            if (parsed.preview() || !parsed.branch().equals(version)) throw new IllegalArgumentException("invalid release branch");
        }
        public ToolchainVersion identity() { return ToolchainVersion.parse(ecosystem, version).orElseThrow(); }
    }

    private final String revision;
    private final List<Branch> branches;
    public ToolchainSupportCatalog(String revision, List<Branch> branches) {
        this.revision = Objects.requireNonNull(revision);
        if (!revision.matches("[A-Za-z0-9._-]{1,96}")) throw new IllegalArgumentException("invalid catalog revision");
        this.branches = List.copyOf(branches);
        if (branches.stream().map(b -> b.ecosystem() + ":" + b.version()).distinct().count() != branches.size())
            throw new IllegalArgumentException("duplicate release branch");
    }
    public String revision() { return revision; }
    public List<Branch> branches() { return branches; }
    public List<Branch> branches(ToolchainEcosystemType ecosystem) {
        return branches.stream().filter(b -> b.ecosystem() == ecosystem)
                .sorted(Comparator.comparing(Branch::identity, ToolchainSupportCatalog::compareBranch)).toList();
    }
    public boolean permits(ToolchainVersion version) {
        return !version.preview() && branches.stream().anyMatch(b -> b.ecosystem() == version.ecosystem()
                && b.version().equals(version.branch()));
    }

    /** Direct branch or at most two increasing fallback branches; never guesses unresolved declarations. / 使用直接分支或最多两个递增回退分支，不猜测未解析声明。 */
    public List<Branch> candidates(ToolchainRequirement requirement) {
        if (requirement.version().isEmpty()) return List.of();
        ToolchainVersion requested = requirement.version().orElseThrow();
        var available = branches(requirement.ecosystem());
        if (requirement.ecosystem() == ToolchainEcosystemType.C || requirement.ecosystem() == ToolchainEcosystemType.CPP)
            return available.stream().filter(b -> b.version().equals(requested.branch()) && !requested.preview()).toList();
        if (!requested.preview() && requirement.constraint() != ToolchainRequirement.ConstraintType.MINIMUM) {
            var exact = available.stream().filter(b -> b.version().equals(requested.branch())).toList();
            if (!exact.isEmpty()) return exact;
        }
        ToolchainVersion requestedBranch = ToolchainVersion.parse(requirement.ecosystem(), requested.branch()).orElseThrow();
        return available.stream().filter(b -> compareBranch(b.identity(), requestedBranch) >= 0).limit(2).toList();
    }

    private static int compareBranch(ToolchainVersion left, ToolchainVersion right) {
        // C99 is older than C11 although its calendar abbreviation is numerically larger. / 虽然年份缩写的数值更大，C99 仍早于 C11。
        if (left.ecosystem() == ToolchainEcosystemType.C || left.ecosystem() == ToolchainEcosystemType.CPP) {
            int a = left.numbers().getFirst(), b = right.numbers().getFirst();
            return Integer.compare(a >= 90 ? 1900 + a : 2000 + a, b >= 90 ? 1900 + b : 2000 + b);
        }
        return left.compareTo(right);
    }

    public static ToolchainSupportCatalog defaults() {
        List<Branch> result = new ArrayList<>();
        add(result, ToolchainEcosystemType.JAVA, ReleaseType.LTS, InstallationType.TEMURIN, "8 11 17 21 25", "");
        add(result, ToolchainEcosystemType.NODE, ReleaseType.LTS, InstallationType.NODE_ARCHIVE, "16 18 20 22 24", "16 18 20");
        add(result, ToolchainEcosystemType.PYTHON, ReleaseType.STABLE, InstallationType.CPYTHON_SOURCE, "3.8 3.9 3.10 3.11 3.12 3.13 3.14", "3.8 3.9");
        add(result, ToolchainEcosystemType.DOTNET, ReleaseType.LTS, InstallationType.DOTNET_SDK, "8 10", "");
        add(result, ToolchainEcosystemType.KOTLIN, ReleaseType.STABLE, InstallationType.KOTLIN_ARCHIVE, "1.8 1.9 2.0 2.1 2.2 2.3 2.4", "1.8 1.9 2.0 2.1 2.2 2.3");
        for (int minor = 18; minor <= 27; minor++) add(result, ToolchainEcosystemType.GO, ReleaseType.STABLE, InstallationType.GO_ARCHIVE,
                "1." + minor, minor < 26 ? "1." + minor : "");
        for (int minor = 56; minor <= 98; minor++) add(result, ToolchainEcosystemType.RUST, ReleaseType.STABLE, InstallationType.RUST_STANDALONE,
                "1." + minor, minor < 98 ? "1." + minor : "");
        add(result, ToolchainEcosystemType.PHP, ReleaseType.STABLE, InstallationType.PHP_SOURCE, "8.0 8.1 8.2 8.3 8.4 8.5", "8.0 8.1");
        add(result, ToolchainEcosystemType.RUBY, ReleaseType.STABLE, InstallationType.RUBY_SOURCE, "3.0 3.1 3.2 3.3 3.4 4.0", "3.0 3.1 3.2");
        add(result, ToolchainEcosystemType.C, ReleaseType.LANGUAGE_STANDARD, InstallationType.SYSTEM_COMPILER, "99 11 17", "");
        add(result, ToolchainEcosystemType.CPP, ReleaseType.LANGUAGE_STANDARD, InstallationType.SYSTEM_COMPILER, "11 14 17 20", "");
        return new ToolchainSupportCatalog("2026-09-09.1", result);
    }
    private static void add(List<Branch> target, ToolchainEcosystemType ecosystem, ReleaseType type,
                            InstallationType installation, String versions, String historical) {
        for (String version : versions.split(" ")) target.add(new Branch(ecosystem, version, type,
                type == ReleaseType.LANGUAGE_STANDARD ? MaintenanceStatus.STANDARD
                        : List.of(historical.split(" ")).contains(version) ? MaintenanceStatus.HISTORICAL : MaintenanceStatus.MAINTAINED,
                installation));
    }
}
