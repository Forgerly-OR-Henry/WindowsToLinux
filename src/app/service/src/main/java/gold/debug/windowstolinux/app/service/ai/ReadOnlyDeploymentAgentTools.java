package gold.debug.windowstolinux.app.service.ai;

import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentPlanner;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.assessment.DeploymentProjectAssessment;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The complete optional-agent tool surface: bounded static analysis and deterministic plan rendering only.
 *
 * <p>完整的可选 Agent 工具表面：仅有有界静态分析和确定性计划渲染。
 */
public final class ReadOnlyDeploymentAgentTools {
    private final DeploymentAnalysisCoordinator analyzer;
    private final ReviewedDeploymentPlanner planner;

    /**
     * Creates a {@code ReadOnlyDeploymentAgentTools} instance.
     *
     * <p>创建 {@code ReadOnlyDeploymentAgentTools} 实例。
     */
    public ReadOnlyDeploymentAgentTools() {
        this(new DeploymentAnalysisCoordinator(), new ReviewedDeploymentPlanner());
    }

    ReadOnlyDeploymentAgentTools(DeploymentAnalysisCoordinator analyzer, ReviewedDeploymentPlanner planner) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    /**
     * Performs bounded static source analysis without running project code.
     *
     * <p>执行有界静态源码分析，而不运行项目代码。
     */
    public DeploymentProjectAssessment analyze(Path sourceRoot, DeploymentProjectType projectType) {
        return analyzer.analyze(sourceRoot, projectType);
    }

    /**
     * Renders a validated plan without opening a remote session or reading any secret.
     *
     * <p>渲染经过校验的计划，不打开远程会话，也不读取任何秘密。
     */
    public ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request) {
        return planner.plan(request);
    }
}
