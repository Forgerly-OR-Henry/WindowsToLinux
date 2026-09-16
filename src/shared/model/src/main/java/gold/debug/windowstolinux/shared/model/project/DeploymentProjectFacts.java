package gold.debug.windowstolinux.shared.model.project;

import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable deterministic facts for one user-selected typed deployment project type; no project code was executed.
 *
 * <p>一个用户选定的部署项目类型的不可变确定性事实；未执行任何项目代码。
 *
 * @param sourceRoot the normalized source root / 规范化的源码根目录
 * @param applicationId the proposed managed identifier / 建议的受管标识
 * @param projectType the selected project type / 选定的项目类型
 * @param buildTool the fixed build entrypoint / 固定构建入口
 * @param support exact support level and validation scope / 精确支持等级与验证范围
 * @param evidence the non-secret deterministic evidence / 非秘密确定性证据
 * @param conflicts the observed conflicting facts / 观察到的冲突事实
 * @param missingInformation required explicit user input / 所需的显式用户输入
 */
public record DeploymentProjectFacts(
        Path sourceRoot,
        String applicationId,
        DeploymentProjectType projectType,
        DeploymentBuildToolType buildTool,
        DeploymentSupportProfile support,
        ProjectLanguageFacts languageFacts,
        List<AnalysisEvidence> evidence,
        List<LocalizedMessage> conflicts,
        List<LocalizedMessage> missingInformation,
        List<gold.debug.windowstolinux.shared.model.toolchain.ToolchainRequirement> toolchainRequirements
) {
    /**
     * Creates a {@code DeploymentProjectFacts} instance.
     *
     * <p>创建 {@code DeploymentProjectFacts} 实例。
     */
    public DeploymentProjectFacts {
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
                && (languageFacts.sourceLanguages().isEmpty()
                || languageFacts.sourceLanguages().stream()
                .anyMatch(language -> language != SourceLanguageType.C && language != SourceLanguageType.CPP))) {
            throw new IllegalArgumentException("CMake facts require a non-empty exact C/C++ source language set");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
        missingInformation = List.copyOf(Objects.requireNonNull(missingInformation, "missingInformation"));
        toolchainRequirements = List.copyOf(Objects.requireNonNull(toolchainRequirements, "toolchainRequirements"));
    }

    /** Existing callers without source toolchain declarations retain their original facts. / 没有工具链声明的既有调用保留原事实。 */
    public DeploymentProjectFacts(Path sourceRoot, String applicationId, DeploymentProjectType projectType,
            DeploymentBuildToolType buildTool, DeploymentSupportProfile support, ProjectLanguageFacts languageFacts,
            List<AnalysisEvidence> evidence, List<LocalizedMessage> conflicts, List<LocalizedMessage> missingInformation) {
        this(sourceRoot, applicationId, projectType, buildTool, support, languageFacts, evidence, conflicts,
                missingInformation, List.of());
    }

    public DeploymentProjectFacts withToolchains(List<gold.debug.windowstolinux.shared.model.toolchain.ToolchainRequirement> requirements) {
        return new DeploymentProjectFacts(sourceRoot, applicationId, projectType, buildTool, support, languageFacts,
                evidence, conflicts, missingInformation, requirements);
    }

    /** Creates facts for callers that have no separate language observations. / 为没有单独语言观测的调用方创建事实。 */
    public DeploymentProjectFacts(Path sourceRoot, String applicationId, DeploymentProjectType projectType,
                                  DeploymentBuildToolType buildTool, ProjectLanguageFacts languageFacts,
                                  List<AnalysisEvidence> evidence, List<LocalizedMessage> conflicts,
                                  List<LocalizedMessage> missingInformation) {
        this(sourceRoot, applicationId, projectType, buildTool, DeploymentSupportCatalog.forArchitecture(projectType, buildTool),
                languageFacts, evidence, conflicts, missingInformation);
    }

    /** Creates facts with the checked-in support claim for callers that have no separate language observations. / 使用已检入支持声明为没有单独语言观测的调用方创建事实。 */
    public DeploymentProjectFacts(Path sourceRoot, String applicationId, DeploymentProjectType projectType,
                                  DeploymentBuildToolType buildTool, List<AnalysisEvidence> evidence,
                                  List<LocalizedMessage> conflicts, List<LocalizedMessage> missingInformation) {
        this(sourceRoot, applicationId, projectType, buildTool, DeploymentSupportCatalog.forArchitecture(projectType, buildTool),
                ProjectLanguageFacts.empty(), evidence, conflicts, missingInformation);
    }

    /**
     * Checks whether the facts have all mandatory deterministic inputs for planning.
     *
     * <p>检查这些事实是否具有计划所需的全部确定性输入。
     *
     * @return whether the project can enter the type-specific planner / 项目是否可进入类型专属计划器
     */
    public boolean readyForPlanning() {
        return projectType.deployable() && support.level().deployable() && conflicts.isEmpty() && missingInformation.isEmpty();
    }
}
