package gold.debug.windowstolinux.mod.deployment.service.standard.staticmode;

import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.DeploymentStandardWorkflow;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardModeSupport;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardRecoverySupport;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.graph.DeploymentStandardGraphPublishRunner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.single.DeploymentStandardSinglePublishRunner;

/** Independent static mode entry using human review and deterministic publication. / 使用人工审阅及确定性发布的独立静态模式入口。 */
public final class DeploymentStaticWorkflow implements Entry {
    /** Shared deterministic pipeline bound to this mode entry. / 绑定当前模式入口的共用确定性流程。 */
    private final DeploymentStandardWorkflow workflow;

    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param references original formal publication and reference flow / 原正式发布及引用流程
     * @param recoverySupport assembled original recovery route / 已装配原恢复路由 */
    public DeploymentStaticWorkflow(ModContext context, DeploymentRefFlow references, DeploymentStandardRecoverySupport recoverySupport) {
        workflow = new DeploymentStandardWorkflow(context, references, support(), recoverySupport, DeploymentStandardSinglePublishRunner::new, DeploymentStandardGraphPublishRunner::new);
    }

    /** Admits one source, review and publication task using captured page inputs. / 使用已捕获页面输入准入一项来源、审阅与发布任务。
     * @param server exact original target server / 精确原目标服务器
     * @param draft captured source and page values / 已捕获来源及页面值
     * @param git optional captured Git source / 可选已捕获 Git 来源
     * @param reviewer original human review adapter / 原人工审阅适配
     * @param approval captured approval policy / 已捕获审批策略
     * @param remember captured exact-action reuse preference / 已捕获精确动作复用偏好
     * @return handle for the admitted static deployment task / 已准入静态部署任务的句柄
     * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public TaskHandle<Void> submit(ServerId server, Draft draft, Optional<GitSource> git, Reviewer reviewer, String approval, boolean remember) throws ApiException {
        return workflow.submit(server, draft, git, reviewer, approval, remember);
    }

    /** Admits one source, review and publication task using captured page inputs. / 使用已捕获页面输入准入一项来源、审阅与发布任务。
     * @param server exact original target server / 精确原目标服务器
     * @param draft captured source and page values / 已捕获来源及页面值
     * @param reviewer original human review adapter / 原人工审阅适配
     * @return handle for the admitted static deployment task / 已准入静态部署任务的句柄
     * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public TaskHandle<Void> submit(ServerId server, Draft draft, Reviewer reviewer) throws ApiException {
        return submit(server, draft, Optional.empty(), reviewer, "MANUAL_REVIEW", true);
    }

    /** Admits one source, review and publication task using captured page inputs. / 使用已捕获页面输入准入一项来源、审阅与发布任务。
     * @param server exact original target server / 精确原目标服务器
     * @param draft captured source and page values / 已捕获来源及页面值
     * @param git optional captured Git source / 可选已捕获 Git 来源
     * @param reviewer original human review adapter / 原人工审阅适配
     * @return handle for the admitted static deployment task / 已准入静态部署任务的句柄
     * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public TaskHandle<Void> submit(ServerId server, Draft draft, Optional<GitSource> git, Reviewer reviewer) throws ApiException {
        return submit(server, draft, git, reviewer, "MANUAL_REVIEW", true);
    }

    /** Validates human or suggested values against the original deterministic form. / 按原确定性表单校验人工或建议值。
     * @param prompt original deterministic review prompt / 原确定性审阅问题
     * @param answer reviewed component answer / 已审阅组件答案 */
    public void validateReview(Prompt prompt, Answers answer) {
        workflow.validateReview(prompt, answer);
    }

    /** Supplies the mode adapter assembled by the standard entry. / 提供标准入口装配的模式适配。
     * @return assembled standard mode adapter / 已装配标准模式适配 */
    public static DeploymentStandardModeSupport support() {
        return new DeploymentStandardModeSupport() {
            /** Identifies whether the captured entry permits assisted extensions. / 标识已捕获入口是否允许辅助扩展。
             * @return false because static mode does not enable assisted extensions / 静态模式不启用辅助扩展，因此为假 */
            public boolean assisted() {
                return false;
            }

            /** Captures the original task mode, approval policy and model revision. / 捕获原任务模式、审批策略及模型修订。
             * @param context original Mod capabilities / 原 Mod 能力
             * @param task original admitted task / 原已准入任务
             * @param approval captured approval policy / 已捕获审批策略
             * @param remember captured exact-action reuse preference / 已捕获精确动作复用偏好
             * @return frozen capabilities and model revision / 冻结能力及模型修订 */
            public Scope freeze(ModContext context, TaskContext task, String approval, boolean remember) {
                return new Scope(context, "");
            }

            /** Rebinds the original captured mode without creating another task. / 重新绑定原捕获模式，不另建任务。
             * @param context original Mod capabilities / 原 Mod 能力
             * @param task original admitted task / 原已准入任务
             * @param revision captured original model revision / 已捕获原模型修订
             * @param approval captured approval policy / 已捕获审批策略
             * @param remember captured exact-action reuse preference / 已捕获精确动作复用偏好
             * @return capabilities rebound to the captured original mode / 重新绑定原捕获模式的能力 */
            public ModContext recover(ModContext context, TaskContext task, String revision, String approval, boolean remember) {
                return context;
            }

            /** Creates task-local mode adapters for the shared deterministic pipeline. / 为共用确定性流程建立任务所属的模式适配。
             * @param context original Mod capabilities / 原 Mod 能力
             * @param validate deterministic review validator / 确定性审阅校验器
             * @return task-local mode extension adapter / 任务所属模式扩展适配 */
            public Session session(ModContext context, java.util.function.BiConsumer<Prompt, Answers> validate) {
                return (server, revision, prompts, reviewer, task) -> reviewer.review(server, revision, prompts, task);
            }
        };
    }

}
