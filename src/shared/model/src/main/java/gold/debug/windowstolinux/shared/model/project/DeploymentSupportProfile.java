package gold.debug.windowstolinux.shared.model.project;

import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.List;
import java.util.Objects;

/**
 * The exact language, architecture, framework, support level, validation matrix, evidence, and limitations for one analyzed path.
 *
 * <p>一个已分析路径的精确语言、架构、框架、支持等级、验证矩阵、证据与限制。
 *
 * @param level evidence-backed support level / 证据支撑的支持等级
 * @param language selected language identity / 选定语言身份
 * @param architecture selected build or delivery architecture / 选定构建或交付架构
 * @param framework selected framework or workload identity / 选定框架或工作负载身份
 * @param validatedTargets real-acceptance targets / 真实验收目标
 * @param evidenceReferences bounded product-entrypoint evidence references / 有界产品入口证据引用
 * @param limitations localized bounded limitations / 本地化有界限制
 */
public record DeploymentSupportProfile(
        DeploymentSupportLevel level,
        SourceLanguageType language,
        String architecture,
        String framework,
        List<ValidatedDeploymentTarget> validatedTargets,
        List<String> evidenceReferences,
        List<LocalizedMessage> limitations
) {
    /** Validates a truthful support claim. / 验证真实的支持声明。 */
    public DeploymentSupportProfile {
        level = Objects.requireNonNull(level, "level");
        language = Objects.requireNonNull(language, "language");
        architecture = bounded(architecture, "architecture");
        framework = bounded(framework, "framework");
        validatedTargets = List.copyOf(Objects.requireNonNull(validatedTargets, "validatedTargets"));
        evidenceReferences = List.copyOf(Objects.requireNonNull(evidenceReferences, "evidenceReferences"));
        evidenceReferences.forEach(item -> bounded(item, "evidenceReference"));
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        limitations.forEach(item -> Objects.requireNonNull(item, "limitation"));
        if (level == DeploymentSupportLevel.FORMALLY_SUPPORTED && validatedTargets.isEmpty()) {
            throw new IllegalArgumentException("formal support requires at least one real validated target");
        }
        if (level == DeploymentSupportLevel.FORMALLY_SUPPORTED && evidenceReferences.isEmpty()) {
            throw new IllegalArgumentException("formal support requires product-entrypoint evidence references");
        }
        if (level != DeploymentSupportLevel.FORMALLY_SUPPORTED && !validatedTargets.isEmpty()) {
            throw new IllegalArgumentException("only formal support may expose validated targets");
        }
        if (level != DeploymentSupportLevel.FORMALLY_SUPPORTED && !evidenceReferences.isEmpty()) {
            throw new IllegalArgumentException("only formal support may expose acceptance evidence references");
        }
    }

    private static String bounded(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank() || value.length() > 512 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must be bounded nonblank text");
        }
        return value;
    }
}
