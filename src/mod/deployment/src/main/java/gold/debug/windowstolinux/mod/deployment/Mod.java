package gold.debug.windowstolinux.mod.deployment;

import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.task.LogCategory;
import gold.debug.windowstolinux.appapi.task.RecoveryRegistration;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSchema;
import gold.debug.windowstolinux.mod.deployment.secret.DeploymentSecretProvisionFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentGitSourceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.DeploymentStandardAnalyzer;

/** Owns official deployment initialization and its platform-bound lifecycle. / 持有官方部署初始化及绑定平台的生命周期。 */
public final class Mod implements ModEntrypoint {
    /** Original published context, absent before startup or after stop. / 原已发布上下文，启动前及停止后不存在。 */
    private ModContext context;

    /** Actual publication task and recovery implementation. / 实际发布任务及恢复实现。 */
    private DeploymentReferenceFlow publicationFlow;

    /** Production Git project source task and original recovery. / 生产 Git 项目来源任务及原恢复。 */
    private DeploymentGitSourceFlow gitSourceFlow;

    /** Production secret revision task and original recovery. / 生产秘密修订任务及原恢复。 */
    private DeploymentSecretProvisionFlow secretProvisionFlow;

    /** Borrowed-material standard analysis entrypoint. / 借用原素材的标准分析入口。 */
    private DeploymentStandardAnalyzer standardAnalyzer;

    /** Active original-task recovery registration. / 活动原任务恢复登记。 */
    private RecoveryRegistration recoveryRegistration;

    /** Original Git source recovery registration. / 原 Git 来源恢复登记。 */
    private RecoveryRegistration gitRecoveryRegistration;

    /** Original secret revision recovery registration. / 原秘密修订恢复登记。 */
    private RecoveryRegistration secretRecoveryRegistration;

    /** Binds owned diagnostics after the platform has prepared the exact business generation. / 平台准备精确业务代次后绑定所属诊断。
     * @param context actual host-created context / 实际宿主创建的上下文
     * @throws Exception if identity or diagnostic registration is unavailable / 身份或诊断登记不可用时 */
    @Override
    public void start(ModContext context) throws Exception {
        if (this.context != null)
            throw new IllegalStateException("Deployment context is already bound");
        DeploymentSchema.requireIdentity(context.db().identity());
        context.logs().register(new LogCategory("deployment", "Deployment", Optional.of("deployment_log")));
        var flow = new DeploymentReferenceFlow(context);
        var registration = context.tasks().registerRecovery(DeploymentReferenceFlow.TASK_TYPE, flow);
        var gitFlow = new DeploymentGitSourceFlow(context);
        RecoveryRegistration gitRegistration;
        try {
            gitRegistration = context.tasks().registerRecovery(DeploymentGitSourceFlow.TASK_TYPE, gitFlow);
        } catch (Exception failure) {
            try {
                registration.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        var secretFlow = new DeploymentSecretProvisionFlow(context);
        RecoveryRegistration secretRegistration;
        try {
            secretRegistration = context.tasks().registerRecovery(DeploymentSecretProvisionFlow.TASK_TYPE, secretFlow);
        } catch (Exception failure) {
            try {
                gitRegistration.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            try {
                registration.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        this.publicationFlow = flow;
        this.gitSourceFlow = gitFlow;
        this.secretProvisionFlow = secretFlow;
        this.standardAnalyzer = new DeploymentStandardAnalyzer();
        this.recoveryRegistration = registration;
        this.gitRecoveryRegistration = gitRegistration;
        this.secretRecoveryRegistration = secretRegistration;
        this.context = context;
    }

    /** Returns the active production publication entrypoint. / 返回活动生产发布入口。
     * @return bound publication flow / 已绑定发布流程 */
    public DeploymentReferenceFlow publicationFlow() {
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

    /** Releases the author reference; platform capabilities own their actual cleanup. / 释放作者引用，平台能力负责其实际清理。
     * @param context original stop reason / 原停止原因
     * @throws Exception if recovery registration cannot close / 恢复登记无法关闭时 */
    @Override
    public void stop(StopContext context) throws Exception {
        Exception failure = null;
        if (secretRecoveryRegistration != null) {
            try {
                secretRecoveryRegistration.close();
                secretRecoveryRegistration = null;
            } catch (Exception unclosed) {
                failure = unclosed;
            }
        }
        if (gitRecoveryRegistration != null) {
            try {
                gitRecoveryRegistration.close();
                gitRecoveryRegistration = null;
            } catch (Exception unclosed) {
                if (failure == null)
                    failure = unclosed;
                else
                    failure.addSuppressed(unclosed);
            }
        }
        if (recoveryRegistration != null) {
            try {
                recoveryRegistration.close();
                recoveryRegistration = null;
            } catch (Exception unclosed) {
                if (failure == null)
                    failure = unclosed;
                else
                    failure.addSuppressed(unclosed);
            }
        }
        if (failure != null)
            throw failure;
        secretProvisionFlow = null;
        standardAnalyzer = null;
        gitSourceFlow = null;
        publicationFlow = null;
        this.context = null;
    }
}
