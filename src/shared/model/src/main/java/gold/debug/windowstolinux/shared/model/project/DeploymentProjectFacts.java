package gold.debug.windowstolinux.shared.model.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

/**
 * Immutable deterministic facts for one user-selected typed deployment project type; no project code was executed.
 *
 *  <p>一个用户选定的部署项目类型的不可变确定性事实；未执行任何项目代码。
 *
 * @param sourceRoot the normalized source root / 规范化的源码根目录
 * @param applicationId the proposed managed identifier / 建议的受管标识
 * @param projectType the selected project type / 选定的项目类型
 * @param buildTool the fixed build entrypoint / 固定构建入口
 * @param support exact support level and validation scope / 精确支持等级与验证范围
 * @param languageFacts language facts / 语言事实
 * @param evidence the non-secret deterministic evidence / 非秘密确定性证据
 * @param conflicts the observed conflicting facts / 观察到的冲突事实
 * @param missingInformation required explicit user input / 所需的显式用户输入
 * @param toolchainRequirements toolchain requirements / 工具链要求集合
 * @param buildDirectory build directory / 构建目录
 */
public record DeploymentProjectFacts(Path sourceRoot, String applicationId, DeploymentProjectType projectType,
        DeploymentBuildToolType buildTool, DeploymentSupportProfile support, ProjectLanguageFacts languageFacts,
        List<AnalysisEvidence> evidence, List<LocalizedMessage> conflicts, List<LocalizedMessage> missingInformation,
        List<gold.debug.windowstolinux.shared.model.toolchain.ToolchainRequirement> toolchainRequirements,
        String buildDirectory) {
    /**
     * Validates and binds the inputs required by deployment project facts.
     * <p>校验并绑定部署项目事实所需输入。
     *
     * @param sourceRoot root of the reviewed source tree / 已审阅源码树的根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param buildTool the fixed build entrypoint / 固定构建入口
     * @param support exact support level and validation scope / 精确支持等级与验证范围
     * @param languageFacts language facts / 语言事实
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @param conflicts the observed conflicting facts / 观察到的冲突事实
     * @param missingInformation required explicit user input / 所需的显式用户输入
     * @param toolchainRequirements toolchain requirements / 工具链要求集合
     * @param buildDirectory build directory / 构建目录
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentProjectFacts {
        buildDirectory = gold.debug.windowstolinux.shared.model.project.application.ApplicationCommand
                .relative(buildDirectory, true);
        sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot").toAbsolutePath().normalize();
        applicationId = Objects.requireNonNull(applicationId, "applicationId").trim();
        if (!applicationId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("applicationId must be a managed application identifier");
        }
        projectType = Objects.requireNonNull(projectType, "projectType");
        buildTool = Objects.requireNonNull(buildTool, "buildTool");
        support = Objects.requireNonNull(support, "support");
        if (projectType.deployable() != support.level().deployable()) {
            throw new IllegalArgumentException("project type and support level must agree on deployment admission");
        }
        if (!projectType.deployable() && buildTool != DeploymentBuildToolType.NONE_PREVIEW) {
            throw new IllegalArgumentException("recognition preview must not expose a build tool");
        }
        languageFacts = Objects.requireNonNull(languageFacts, "languageFacts");
        if (projectType == DeploymentProjectType.CMAKE_SERVICE
                && (languageFacts.sourceLanguages().isEmpty() || languageFacts.sourceLanguages().stream().anyMatch(
                        language -> language != SourceLanguageType.C && language != SourceLanguageType.CPP))) {
            throw new IllegalArgumentException("CMake facts require a non-empty exact C/C++ source language set");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
        missingInformation = List.copyOf(Objects.requireNonNull(missingInformation, "missingInformation"));
        toolchainRequirements = List.copyOf(Objects.requireNonNull(toolchainRequirements, "toolchainRequirements"));
    }

    /**
     * Initializes deployment project facts through its shared constructor contract.
     * <p>通过共享构造契约初始化部署项目事实。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param tool tool / 工具
     * @param support exact support level and validation scope / 精确支持等级与验证范围
     * @param language selected language identity / 选定语言身份
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @param conflicts the observed conflicting facts / 观察到的冲突事实
     * @param missing missing / 缺失
     * @param requirements requirements / 要求集合
     */
    public DeploymentProjectFacts(Path root, String id, DeploymentProjectType type, DeploymentBuildToolType tool,
            DeploymentSupportProfile support, ProjectLanguageFacts language, List<AnalysisEvidence> evidence,
            List<LocalizedMessage> conflicts, List<LocalizedMessage> missing,
            List<gold.debug.windowstolinux.shared.model.toolchain.ToolchainRequirement> requirements) {
        this(root, id, type, tool, support, language, evidence, conflicts, missing, requirements, "");
    }

    /**
     * Builds deployment project facts from the supplied in bundle inputs.
     * <p>根据所提供在资源包输入构建部署项目事实。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @param bundleApplicationId bundle application id / 资源包应用标识
     * @return deployment project facts from the supplied in bundle inputs / 根据所提供在资源包输入构建部署项目事实
     */
    public DeploymentProjectFacts inBundle(Path root, String directory, String bundleApplicationId) {
        return new DeploymentProjectFacts(root, bundleApplicationId, projectType, buildTool, support, languageFacts,
                evidence, conflicts, missingInformation, toolchainRequirements, directory);
    }

    /**
     * Existing callers without source toolchain declarations retain their original facts. / 没有工具链声明的既有调用保留原事实。
     *
     * @param sourceRoot root of the reviewed source tree / 已审阅源码树的根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param buildTool the fixed build entrypoint / 固定构建入口
     * @param support exact support level and validation scope / 精确支持等级与验证范围
     * @param languageFacts language facts / 语言事实
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @param conflicts the observed conflicting facts / 观察到的冲突事实
     * @param missingInformation required explicit user input / 所需的显式用户输入
     */
    public DeploymentProjectFacts(Path sourceRoot, String applicationId, DeploymentProjectType projectType,
            DeploymentBuildToolType buildTool, DeploymentSupportProfile support, ProjectLanguageFacts languageFacts,
            List<AnalysisEvidence> evidence, List<LocalizedMessage> conflicts,
            List<LocalizedMessage> missingInformation) {
        this(sourceRoot, applicationId, projectType, buildTool, support, languageFacts, evidence, conflicts,
                missingInformation, List.of());
    }

    /**
     * Returns the contract with the supplied toolchains applied.
     * <p>返回应用所提供工具链集合后的契约。
     *
     * @param requirements requirements / 要求集合
     * @return the contract with the supplied toolchains applied / 应用所提供工具链集合后的契约
     */
    public DeploymentProjectFacts withToolchains(
            List<gold.debug.windowstolinux.shared.model.toolchain.ToolchainRequirement> requirements) {
        return new DeploymentProjectFacts(sourceRoot, applicationId, projectType, buildTool, support, languageFacts,
                evidence, conflicts, missingInformation, requirements, buildDirectory);
    }

    /**
     * Creates facts for callers that have no separate language observations. / 为没有单独语言观测的调用方创建事实。
     *
     * @param sourceRoot root of the reviewed source tree / 已审阅源码树的根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param buildTool the fixed build entrypoint / 固定构建入口
     * @param languageFacts language facts / 语言事实
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @param conflicts the observed conflicting facts / 观察到的冲突事实
     * @param missingInformation required explicit user input / 所需的显式用户输入
     */
    public DeploymentProjectFacts(Path sourceRoot, String applicationId, DeploymentProjectType projectType,
            DeploymentBuildToolType buildTool, ProjectLanguageFacts languageFacts, List<AnalysisEvidence> evidence,
            List<LocalizedMessage> conflicts, List<LocalizedMessage> missingInformation) {
        this(sourceRoot, applicationId, projectType, buildTool,
                DeploymentSupportCatalog.forArchitecture(projectType, buildTool), languageFacts, evidence, conflicts,
                missingInformation);
    }

    /**
     * Creates facts with the checked-in support claim for callers that have no separate language observations. / 使用已检入支持声明为没有单独语言观测的调用方创建事实。
     *
     * @param sourceRoot root of the reviewed source tree / 已审阅源码树的根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param buildTool the fixed build entrypoint / 固定构建入口
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @param conflicts the observed conflicting facts / 观察到的冲突事实
     * @param missingInformation required explicit user input / 所需的显式用户输入
     */
    public DeploymentProjectFacts(Path sourceRoot, String applicationId, DeploymentProjectType projectType,
            DeploymentBuildToolType buildTool, List<AnalysisEvidence> evidence, List<LocalizedMessage> conflicts,
            List<LocalizedMessage> missingInformation) {
        this(sourceRoot, applicationId, projectType, buildTool,
                DeploymentSupportCatalog.forArchitecture(projectType, buildTool), ProjectLanguageFacts.empty(),
                evidence, conflicts, missingInformation);
    }

    /**
     * Checks whether the facts have all mandatory deterministic inputs for planning.
     *
     *  <p>检查这些事实是否具有计划所需的全部确定性输入。
     *
     * @return whether the project can enter the type-specific planner / 项目是否可进入类型专属计划器
     */
    public boolean readyForPlanning() {
        return projectType.deployable() && support.level().deployable() && conflicts.isEmpty()
                && missingInformation.isEmpty();
    }
}
