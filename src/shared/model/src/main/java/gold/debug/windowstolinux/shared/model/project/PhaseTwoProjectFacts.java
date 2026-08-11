package gold.debug.windowstolinux.shared.model.project;

import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable deterministic facts for one user-selected Phase Two project type; no project code was executed.
 *
 * <p>一个用户选定的二期项目类型的不可变确定性事实；未执行任何项目代码。
 *
 * @param sourceRoot the normalized source root / 规范化的源码根目录
 * @param applicationId the proposed managed identifier / 建议的受管标识
 * @param projectType the selected project type / 选定的项目类型
 * @param buildTool the fixed build entrypoint / 固定构建入口
 * @param evidence the non-secret deterministic evidence / 非秘密确定性证据
 * @param conflicts the observed conflicting facts / 观察到的冲突事实
 * @param missingInformation required explicit user input / 所需的显式用户输入
 */
public record PhaseTwoProjectFacts(
        Path sourceRoot,
        String applicationId,
        PhaseTwoProjectType projectType,
        PhaseTwoBuildTool buildTool,
        List<AnalysisEvidence> evidence,
        List<LocalizedMessage> conflicts,
        List<LocalizedMessage> missingInformation
) {
    /**
     * Creates a {@code PhaseTwoProjectFacts} instance.
     *
     * <p>创建 {@code PhaseTwoProjectFacts} 实例。
     */
    public PhaseTwoProjectFacts {
        sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot").toAbsolutePath().normalize();
        applicationId = Objects.requireNonNull(applicationId, "applicationId").trim();
        if (!applicationId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("applicationId must be a managed application identifier");
        }
        projectType = Objects.requireNonNull(projectType, "projectType");
        buildTool = Objects.requireNonNull(buildTool, "buildTool");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
        missingInformation = List.copyOf(Objects.requireNonNull(missingInformation, "missingInformation"));
    }

    /**
     * Checks whether the facts have all mandatory deterministic inputs for planning.
     *
     * <p>检查这些事实是否具有计划所需的全部确定性输入。
     *
     * @return whether the project can enter the type-specific planner / 项目是否可进入类型专属计划器
     */
    public boolean readyForPlanning() {
        return conflicts.isEmpty() && missingInformation.isEmpty();
    }
}
