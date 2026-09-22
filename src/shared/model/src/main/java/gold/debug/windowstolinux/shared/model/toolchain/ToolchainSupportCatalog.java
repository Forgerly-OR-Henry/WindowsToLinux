package gold.debug.windowstolinux.shared.model.toolchain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The single, injectable build-release allowlist; release discovery supplies patch versions. / 唯一可注入构建分支目录，补丁由官方发布发现提供。
 */
public final class ToolchainSupportCatalog {
    /**
     * Classifies toolchain release stability for supported branch selection.
     * <p>对工具链发布稳定性分类以选择支持分支。
     */
    public enum ReleaseType {
        /**
         * LTS classification within release type.
         * <p>发布类型中的LTS分类。
         */
        LTS,
        /**
         * STABLE classification within release type.
         * <p>发布类型中的稳定分类。
         */
        STABLE,
        /**
         * LANGUAGE STANDARD classification within release type.
         * <p>发布类型中的语言标准分类。
         */
        LANGUAGE_STANDARD
    }

    /**
     * Records the catalog's support status for a toolchain branch.
     * <p>记录目录中工具链分支的支持状态。
     */
    public enum MaintenanceStatus {
        /**
         * MAINTAINED classification within maintenance status.
         * <p>维护状态中的维护中分类。
         */
        MAINTAINED,
        /**
         * HISTORICAL classification within maintenance status.
         * <p>维护状态中的历史分类。
         */
        HISTORICAL,
        /**
         * STANDARD classification within maintenance status.
         * <p>维护状态中的标准分类。
         */
        STANDARD
    }

    /**
     * Selects how a supported toolchain branch is obtained or reused.
     * <p>选择获取或复用受支持工具链分支的方式。
     */
    public enum InstallationType {
        /**
         * TEMURIN classification within installation type.
         * <p>安装类型中的TEMURIN分类。
         */
        TEMURIN,
        /**
         * NODE ARCHIVE classification within installation type.
         * <p>安装类型中的节点归档分类。
         */
        NODE_ARCHIVE,
        /**
         * CPYTHON SOURCE classification within installation type.
         * <p>安装类型中的CPYTHON源码分类。
         */
        CPYTHON_SOURCE,
        /**
         * DOTNET SDK classification within installation type.
         * <p>安装类型中的DOTNETSDK分类。
         */
        DOTNET_SDK,
        /**
         * KOTLIN ARCHIVE classification within installation type.
         * <p>安装类型中的KOTLIN归档分类。
         */
        KOTLIN_ARCHIVE,
        /**
         * GO ARCHIVE classification within installation type.
         * <p>安装类型中的GO归档分类。
         */
        GO_ARCHIVE,
        /**
         * RUST STANDALONE classification within installation type.
         * <p>安装类型中的RUST独立分类。
         */
        RUST_STANDALONE,
        /**
         * PHP SOURCE classification within installation type.
         * <p>安装类型中的PHP源码分类。
         */
        PHP_SOURCE,
        /**
         * RUBY SOURCE classification within installation type.
         * <p>安装类型中的Ruby源码分类。
         */
        RUBY_SOURCE,
        /**
         * SYSTEM COMPILER classification within installation type.
         * <p>安装类型中的系统编译器分类。
         */
        SYSTEM_COMPILER
    }

    /**
     * Describes one supported toolchain branch and its installation policy.
     * <p>描述一个受支持工具链分支及其安装策略。
     *
     * @param ecosystem ecosystem / 生态
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param releaseType release type / 发布类型
     * @param maintenance maintenance / 维护
     * @param installation installation / 安装
     */
    public record Branch(ToolchainEcosystemType ecosystem, String version, ReleaseType releaseType,
            MaintenanceStatus maintenance, InstallationType installation) {
        /**
         * Validates and binds the inputs required by branch.
         * <p>校验并绑定分支所需输入。
         *
         * @param ecosystem ecosystem / 生态
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param releaseType release type / 发布类型
         * @param maintenance maintenance / 维护
         * @param installation installation / 安装
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Branch {
            Objects.requireNonNull(ecosystem);
            Objects.requireNonNull(releaseType);
            Objects.requireNonNull(maintenance);
            Objects.requireNonNull(installation);
            ToolchainVersion parsed = ToolchainVersion.parse(ecosystem, version).orElseThrow();
            if (parsed.preview() || !parsed.branch().equals(version))
                throw new IllegalArgumentException("invalid release branch");
        }

        /**
         * Returns identity.
         * <p>返回身份。
         *
         * @return identity / 身份
         */
        public ToolchainVersion identity() {
            return ToolchainVersion.parse(ecosystem, version).orElseThrow();
        }
    }

    /**
     * Immutable configuration or secret revision number.
     * <p>不可变配置或秘密修订号。
     */
    private final String revision;

    /**
     * Returns branches for the selected ecosystem ordered by the catalog's version comparison.
     * <p>返回所选生态的分支，并按目录的版本比较规则排序。
     */
    private final List<Branch> branches;
    /**
     * Validates and binds the inputs required by toolchain support catalog.
     * <p>校验并绑定工具链支持目录所需输入。
     *
     * @param revision immutable configuration or secret revision number / 不可变配置或秘密修订号
     * @param branches supported toolchain release branches / 受支持工具链发布分支
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ToolchainSupportCatalog(String revision, List<Branch> branches) {
        this.revision = Objects.requireNonNull(revision);
        if (!revision.matches("[A-Za-z0-9._-]{1,96}"))
            throw new IllegalArgumentException("invalid catalog revision");
        this.branches = List.copyOf(branches);
        if (branches.stream().map(b -> b.ecosystem() + ":" + b.version()).distinct().count() != branches.size())
            throw new IllegalArgumentException("duplicate release branch");
    }

    /**
     * Returns immutable configuration or secret revision number.
     * <p>返回不可变配置或秘密修订号。
     *
     * @return immutable configuration or secret revision number / 不可变配置或秘密修订号
     */
    public String revision() {
        return revision;
    }

    /**
     * Returns branches for the selected ecosystem ordered by the catalog's version comparison.
     * <p>返回所选生态的分支，并按目录的版本比较规则排序。
     *
     * @return branches for the selected ecosystem ordered by the catalog's version comparison / 所选生态的分支，并按目录的版本比较规则排序
     */
    public List<Branch> branches() {
        return branches;
    }

    /**
     * Returns branches for the selected ecosystem ordered by the catalog's version comparison.
     * <p>返回所选生态的分支，并按目录的版本比较规则排序。
     *
     * @param ecosystem ecosystem / 生态
     * @return branches for the selected ecosystem ordered by the catalog's version comparison / 所选生态的分支，并按目录的版本比较规则排序
     */
    public List<Branch> branches(ToolchainEcosystemType ecosystem) {
        return branches.stream().filter(b -> b.ecosystem() == ecosystem)
                .sorted(Comparator.comparing(Branch::identity, ToolchainSupportCatalog::compareBranch)).toList();
    }

    /**
     * Tests the permits predicate against the supplied evidence.
     * <p>根据所提供证据检查允许条件。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @return true when permits predicate against the supplied evidence, false otherwise / 根据所提供证据检查允许条件时为 true，否则为 false
     */
    public boolean permits(ToolchainVersion version) {
        return !version.preview() && branches.stream()
                .anyMatch(b -> b.ecosystem() == version.ecosystem() && b.version().equals(version.branch()));
    }

    /**
     * Direct branch or at most two increasing fallback branches; never guesses unresolved declarations. / 使用直接分支或最多两个递增回退分支，不猜测未解析声明。
     *
     * @param requirement requirement / 要求
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    public List<Branch> candidates(ToolchainRequirement requirement) {
        if (requirement.version().isEmpty())
            return List.of();
        ToolchainVersion requested = requirement.version().orElseThrow();
        var available = branches(requirement.ecosystem());
        if (requirement.ecosystem() == ToolchainEcosystemType.C
                || requirement.ecosystem() == ToolchainEcosystemType.CPP)
            return available.stream().filter(b -> b.version().equals(requested.branch()) && !requested.preview())
                    .toList();
        if (!requested.preview() && requirement.constraint() != ToolchainRequirement.ConstraintType.MINIMUM) {
            var exact = available.stream().filter(b -> b.version().equals(requested.branch())).toList();
            if (!exact.isEmpty())
                return exact;
        }
        ToolchainVersion requestedBranch = ToolchainVersion.parse(requirement.ecosystem(), requested.branch())
                .orElseThrow();
        return available.stream().filter(b -> compareBranch(b.identity(), requestedBranch) >= 0).limit(2).toList();
    }

    /**
     * Compares branch.
     * <p>比较分支。
     *
     * @param left left / 左侧
     * @param right right / 右侧
     * @return compare branch as a numeric result / 比较分支的数值结果
     */
    private static int compareBranch(ToolchainVersion left, ToolchainVersion right) {
        // C99 is older than C11 although its calendar abbreviation is numerically larger. / 虽然年份缩写的数值更大，C99 仍早于 C11。
        if (left.ecosystem() == ToolchainEcosystemType.C || left.ecosystem() == ToolchainEcosystemType.CPP) {
            int a = left.numbers().getFirst(), b = right.numbers().getFirst();
            return Integer.compare(a >= 90 ? 1900 + a : 2000 + a, b >= 90 ? 1900 + b : 2000 + b);
        }
        return left.compareTo(right);
    }

    /**
     * Builds the shipped supported toolchain branches and preparation strategies from fixed catalog declarations.
     * <p>根据固定目录声明构建随附的受支持工具链分支及准备策略。
     *
     * @return the shipped supported toolchain branches and preparation strategies from fixed catalog declarations / 根据固定目录声明构建随附的受支持工具链分支及准备策略
     */
    public static ToolchainSupportCatalog defaults() {
        List<Branch> result = new ArrayList<>();
        add(result, ToolchainEcosystemType.JAVA, ReleaseType.LTS, InstallationType.TEMURIN, "8 11 17 21 25", "");
        add(result, ToolchainEcosystemType.NODE, ReleaseType.LTS, InstallationType.NODE_ARCHIVE, "16 18 20 22 24",
                "16 18 20");
        add(result, ToolchainEcosystemType.PYTHON, ReleaseType.STABLE, InstallationType.CPYTHON_SOURCE,
                "3.8 3.9 3.10 3.11 3.12 3.13 3.14", "3.8 3.9");
        add(result, ToolchainEcosystemType.DOTNET, ReleaseType.LTS, InstallationType.DOTNET_SDK, "8 10", "");
        add(result, ToolchainEcosystemType.KOTLIN, ReleaseType.STABLE, InstallationType.KOTLIN_ARCHIVE,
                "1.8 1.9 2.0 2.1 2.2 2.3 2.4", "1.8 1.9 2.0 2.1 2.2 2.3");
        for (int minor = 18; minor <= 27; minor++)
            add(result, ToolchainEcosystemType.GO, ReleaseType.STABLE, InstallationType.GO_ARCHIVE, "1." + minor,
                    minor < 26 ? "1." + minor : "");
        for (int minor = 56; minor <= 98; minor++)
            add(result, ToolchainEcosystemType.RUST, ReleaseType.STABLE, InstallationType.RUST_STANDALONE, "1." + minor,
                    minor < 98 ? "1." + minor : "");
        add(result, ToolchainEcosystemType.PHP, ReleaseType.STABLE, InstallationType.PHP_SOURCE,
                "8.0 8.1 8.2 8.3 8.4 8.5", "8.0 8.1");
        add(result, ToolchainEcosystemType.RUBY, ReleaseType.STABLE, InstallationType.RUBY_SOURCE,
                "3.0 3.1 3.2 3.3 3.4 4.0", "3.0 3.1 3.2");
        add(result, ToolchainEcosystemType.C, ReleaseType.LANGUAGE_STANDARD, InstallationType.SYSTEM_COMPILER,
                "99 11 17", "");
        add(result, ToolchainEcosystemType.CPP, ReleaseType.LANGUAGE_STANDARD, InstallationType.SYSTEM_COMPILER,
                "11 14 17 20", "");
        return new ToolchainSupportCatalog("2026-09-09.1", result);
    }

    /**
     * Adds toolchain support catalog.
     * <p>添加工具链支持目录。
     *
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param ecosystem ecosystem / 生态
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param installation installation / 安装
     * @param versions versions / 版本集合
     * @param historical historical / 历史
     */
    private static void add(List<Branch> target, ToolchainEcosystemType ecosystem, ReleaseType type,
            InstallationType installation, String versions, String historical) {
        for (String version : versions.split(" "))
            target.add(new Branch(ecosystem, version, type,
                    type == ReleaseType.LANGUAGE_STANDARD
                            ? MaintenanceStatus.STANDARD
                            : List.of(historical.split(" ")).contains(version)
                                    ? MaintenanceStatus.HISTORICAL
                                    : MaintenanceStatus.MAINTAINED,
                    installation));
    }
}
