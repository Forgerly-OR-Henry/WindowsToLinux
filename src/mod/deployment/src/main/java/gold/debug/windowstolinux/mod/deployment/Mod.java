package gold.debug.windowstolinux.mod.deployment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.mod.lifecycle.StopContext;
import gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode;
import gold.debug.windowstolinux.appapi.task.log.LogCategory;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApi;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSchema;
import gold.debug.windowstolinux.mod.deployment.secret.DeploymentSecretProvisionFlow;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentPageService;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentWorkflow;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentGitSourceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentApiProvider;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentApplicationsService;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticTaskRecovery;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticWorkflow;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication.DeploymentStandardPublishRecovery;
import gold.debug.windowstolinux.mod.deployment.ui.DeploymentAgentPage;
import gold.debug.windowstolinux.mod.deployment.ui.DeploymentApplicationsPage;
import gold.debug.windowstolinux.mod.deployment.ui.DeploymentStaticPage;

/** Owns official deployment initialization and its platform-bound lifecycle. / 持有官方部署初始化及绑定平台的生命周期。 */
public final class Mod implements ModEntrypoint {
    /** Creates an inactive deployment Mod for platform lifecycle binding. / 创建等待平台生命周期绑定的未启用部署 Mod。 */
    public Mod() {
    }

    /** Original published context, absent before startup or after stop. / 原已发布上下文，启动前及停止后不存在。 */
    private ModContext context;

    /** Actual publication task and recovery implementation. / 实际发布任务及恢复实现。 */
    private DeploymentRefFlow publicationFlow;

    /** Production Git project source task and original recovery. / 生产 Git 项目来源任务及原恢复。 */
    private DeploymentGitSourceFlow gitSourceFlow;

    /** Production secret revision task and original recovery. / 生产秘密修订任务及原恢复。 */
    private DeploymentSecretProvisionFlow secretProvisionFlow;

    /** Borrowed-material standard analysis entrypoint. / 借用原素材的标准分析入口。 */
    private DeploymentStandardAnalyzer standardAnalyzer;

    /** Borrowed-material deterministic deployment planning entrypoint. / 借用原素材的确定性部署规划入口。 */
    private DeploymentStandardPlanner standardPlanner;

    /** One original-task static review coordinator. / 单项原任务静态审阅协调器。 */
    private DeploymentStaticWorkflow staticWorkflow;

    /** Mod-level page query and running-task state. / Mod 级页面查询及运行任务状态。 */
    private DeploymentStaticPageService pageService;

    /** Autonomous original-task workflow and recovery. / 自主原任务流程及恢复。 */
    private DeploymentAgentWorkflow agentWorkflow;

    /** Autonomous page query and submission service. / 自主页面查询与提交服务。 */
    private DeploymentAgentPageService agentPageService;

    /** Successful platform registrations in acquisition order, retained if close fails. / 按取得顺序保存成功平台登记，关闭失败时保留。 */
    private final List<AutoCloseable> registrations = new ArrayList<>();

    /** Binds owned diagnostics after the platform has prepared the exact business generation. / 平台准备精确业务代次后绑定所属诊断。
     * @param context actual host-created context / 实际宿主创建的上下文
     * @throws Exception if identity or diagnostic registration is unavailable / 身份或诊断登记不可用时 */
    @Override
    public void start(ModContext context) throws Exception {
        if (this.context != null || !registrations.isEmpty())
            throw new IllegalStateException("Deployment context is already bound or retained");
        DeploymentSchema.requireIdentity(context.db().identity());
        context.logs().register(new LogCategory("deployment", "Deployment", Optional.of("deployment_log")));
        var flow = new DeploymentRefFlow(context, new DeploymentStandardPublishRecovery(context));
        var gitFlow = new DeploymentGitSourceFlow(context);
        var secretFlow = new DeploymentSecretProvisionFlow(context);
        var workflow = new DeploymentStaticWorkflow(context, flow);
        var pageTasks = new DeploymentStaticPageService(context, workflow);
        var autonomous = new DeploymentAgentWorkflow(context);
        var autonomousPageTasks = new DeploymentAgentPageService(context, autonomous);
        var applicationTasks = new DeploymentApplicationsService(context);
        var deploymentApi = new DeploymentApiProvider(context);
        try {
            registrations.add(context.tasks().registerRecovery(DeploymentRefFlow.TASK_TYPE,
                new DeploymentStaticTaskRecovery(context, flow)));
            registrations.add(context.tasks().registerRecovery(DeploymentGitSourceFlow.TASK_TYPE, gitFlow));
            registrations.add(context.tasks().registerRecovery(DeploymentSecretProvisionFlow.TASK_TYPE, secretFlow));
            registrations
                .add(context.tasks().registerRecovery(DeploymentStaticPageService.SERVER_LIST_TASK, pageTasks));
            registrations
                .add(context.tasks().registerRecovery(DeploymentStaticPageService.SECRET_LIST_TASK, pageTasks));
            registrations.add(context.tasks().registerRecovery(DeploymentAgentWorkflow.TASK_TYPE, autonomous));
            registrations.add(
                context.tasks().registerRecovery(DeploymentAgentPageService.SERVER_LIST_TASK, autonomousPageTasks));
            registrations
                .add(context.tasks().registerRecovery(DeploymentApplicationsService.QUERY_TASK, applicationTasks));
            registrations
                .add(context.tasks().registerRecovery(DeploymentApplicationsService.ACTION_TASK, applicationTasks));
            registrations.add(context.services().register(DeploymentApi.class, deploymentApi));
            if (context.launchMode() == ModLaunchMode.NORMAL) {
                for (var kind : java.util.List.of(
                    gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind.GO_SERVICE,
                    gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind.NODE_SERVICE))
                    registrations.add(context.ui().registerPreview(
                        new PageSpec("runtime-preview-" + kind.name().toLowerCase(java.util.Locale.ROOT),
                            "deployment.preview.runtime", "Runtime review: " + kind.name(), true),
                        () -> new gold.debug.windowstolinux.mod.deployment.ui.DeploymentRuntimePreview(kind)));

                registrations.add(context.ui().register(new PageSpec("workspace", "deployment.workspace.title",
                    "Deployment", false), () -> new gold.debug.windowstolinux.mod.deployment.ui.DeploymentWorkspace(
                    () -> new DeploymentStaticPage(pageTasks), () -> new DeploymentAgentPage(autonomousPageTasks),
                    () -> new DeploymentApplicationsPage(applicationTasks))));
            }
        } catch (Exception failure) {
            closeRegistrations(failure);
            throw failure;
        }
        this.publicationFlow = flow;
        this.gitSourceFlow = gitFlow;
        this.secretProvisionFlow = secretFlow;
        this.standardAnalyzer = new DeploymentStandardAnalyzer();
        this.standardPlanner = new DeploymentStandardPlanner();
        this.staticWorkflow = workflow;
        this.pageService = pageTasks;
        this.agentWorkflow = autonomous;
        this.agentPageService = autonomousPageTasks;
        this.context = context;
    }

    /** Returns the active production publication entrypoint. / 返回活动生产发布入口。
     * @return bound publication flow / 已绑定发布流程 */
    public DeploymentRefFlow publicationFlow() {
        if (publicationFlow == null)
            throw new IllegalStateException("Deployment Mod is not started");
        return publicationFlow;
    }

    /** Returns the active production Git project source entrypoint. / 返回活动的生产 Git 项目来源入口。
     * @return bound task flow / 已绑定任务流程 */
    public DeploymentGitSourceFlow gitSourceFlow() {
        if (gitSourceFlow == null)
            throw new IllegalStateException("Deployment Mod is not started");
        return gitSourceFlow;
    }

    /** Returns the active production secret revision entrypoint. / 返回活动生产秘密修订入口。
     * @return bound task flow / 已绑定任务流程 */
    public DeploymentSecretProvisionFlow secretProvisionFlow() {
        if (secretProvisionFlow == null)
            throw new IllegalStateException("Deployment Mod is not started");
        return secretProvisionFlow;
    }

    /** Returns the active deterministic standard analysis entrypoint. / 返回活动的确定性标准分析入口。
     * @return bound analysis service / 已绑定分析服务 */
    public DeploymentStandardAnalyzer standardAnalyzer() {
        if (standardAnalyzer == null)
            throw new IllegalStateException("Deployment Mod is not started");
        return standardAnalyzer;
    }

    /** Returns the active deterministic standard planner. / 返回活动的确定性标准规划器。
     * @return bound planner / 已绑定规划器 */
    public DeploymentStandardPlanner standardPlanner() {
        if (standardPlanner == null)
            throw new IllegalStateException("Deployment Mod is not started");
        return standardPlanner;
    }

    /** Releases the author reference; platform capabilities own their actual cleanup. / 释放作者引用，平台能力负责其实际清理。
     * @param context original stop reason / 原停止原因
     * @throws Exception if recovery registration cannot close / 恢复登记无法关闭时 */
    @Override
    public void stop(StopContext context) throws Exception {
        Exception failure = closeRegistrations(null);
        if (failure != null)
            throw failure;
        secretProvisionFlow = null;
        agentWorkflow = null;
        agentPageService = null;
        staticWorkflow = null;
        pageService = null;
        standardAnalyzer = null;
        standardPlanner = null;
        gitSourceFlow = null;
        publicationFlow = null;
        this.context = null;
    }

    /** Closes owned registrations in reverse order and retains every unconfirmed close for retry. / 逆序关闭所属登记并保留每项未确认关闭供重试。
     * @param failure original failure or null / 原失败或空
     * @return original or first close failure, null after confirmed cleanup / 原失败或首次关闭失败，确认清理后为空 */
    private Exception closeRegistrations(Exception failure) {
        for (int index = registrations.size() - 1; index >= 0; index--)
            try {
                registrations.get(index).close();
                registrations.remove(index);
            } catch (Exception unclosed) {
                if (failure == null)
                    failure = unclosed;
                else
                    failure.addSuppressed(unclosed);
            }
        return failure;
    }

}
