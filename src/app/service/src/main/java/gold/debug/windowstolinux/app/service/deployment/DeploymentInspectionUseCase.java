package gold.debug.windowstolinux.app.service.deployment;

import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentPlanner;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.assessment.DeploymentProjectAssessment;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Provides bounded deterministic source analysis and deployment plan rendering.
 *
 *  <p>提供有界的确定性源码分析和部署计划生成。
 */
public final class DeploymentInspectionUseCase {
    /**
     * Bound deployment analysis coordinator collaborator for analyzer.
     * <p>处理分析器的部署分析协调器协作对象。
     */
    private final DeploymentAnalysisCoordinator analyzer;
    /**
     * Bound reviewed deployment planner collaborator for planner.
     * <p>处理规划器的已审阅部署规划器协作对象。
     */
    private final ReviewedDeploymentPlanner planner;

    /**
     * Creates the deterministic deployment inspection use case.
     * <p>创建确定性部署检查用例。
     */
    public DeploymentInspectionUseCase() {
        this(new DeploymentAnalysisCoordinator(), new ReviewedDeploymentPlanner());
    }

    /**
     * Binds deterministic source analysis and deployment planning.
     * <p>绑定确定性源码分析和部署规划。
     *
     * @param analyzer analyzer / 分析器
     * @param planner planner / 规划器
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    DeploymentInspectionUseCase(DeploymentAnalysisCoordinator analyzer, ReviewedDeploymentPlanner planner) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    /**
     * Performs bounded static source analysis without running project code.
     *
     *  <p>执行有界静态源码分析，而不运行项目代码。
     *
     * @param sourceRoot root of the reviewed source tree / 已审阅源码树的根目录
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @return constructed or resolved deployment project assessment / 构造或解析得到的部署项目评估
     */
    public DeploymentProjectAssessment analyze(Path sourceRoot, DeploymentProjectType projectType) {
        return analyzer.analyze(sourceRoot, projectType);
    }

    /**
     * Renders a validated plan without opening a remote session or reading any secret.
     *
     *  <p>渲染经过校验的计划，不打开远程会话，也不读取任何秘密。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved reviewed deployment plan / 构造或解析得到的已审阅部署计划
     */
    public ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request) {
        return planner.plan(request);
    }
}
