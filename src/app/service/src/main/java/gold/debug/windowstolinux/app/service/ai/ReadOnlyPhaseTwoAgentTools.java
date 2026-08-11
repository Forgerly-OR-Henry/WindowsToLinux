package gold.debug.windowstolinux.app.service.ai;

import gold.debug.windowstolinux.shared.analyze.core.PhaseTwoProjectAnalyzer;
import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentPlanner;
import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentRequest;
import gold.debug.windowstolinux.shared.model.analysis.PhaseTwoProjectAssessment;
import gold.debug.windowstolinux.shared.model.project.PhaseTwoProjectType;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The complete optional-agent tool surface: bounded static analysis and deterministic plan rendering only.
 *
 * <p>完整的可选 Agent 工具表面：仅有有界静态分析和确定性计划渲染。
 */
public final class ReadOnlyPhaseTwoAgentTools {
    private final PhaseTwoProjectAnalyzer analyzer;
    private final PhaseTwoDeploymentPlanner planner;

    /**
     * Creates a {@code ReadOnlyPhaseTwoAgentTools} instance.
     *
     * <p>创建 {@code ReadOnlyPhaseTwoAgentTools} 实例。
     */
    public ReadOnlyPhaseTwoAgentTools() {
        this(new PhaseTwoProjectAnalyzer(), new PhaseTwoDeploymentPlanner());
    }

    ReadOnlyPhaseTwoAgentTools(PhaseTwoProjectAnalyzer analyzer, PhaseTwoDeploymentPlanner planner) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    /**
     * Performs bounded static source analysis without running project code.
     *
     * <p>执行有界静态源码分析，而不运行项目代码。
     */
    public PhaseTwoProjectAssessment analyze(Path sourceRoot, PhaseTwoProjectType projectType) {
        return analyzer.analyze(sourceRoot, projectType);
    }

    /**
     * Renders a validated plan without opening a remote session or reading any secret.
     *
     * <p>渲染经过校验的计划，不打开远程会话，也不读取任何秘密。
     */
    public PhaseTwoDeploymentPlan plan(PhaseTwoDeploymentRequest request) {
        return planner.plan(request);
    }
}
