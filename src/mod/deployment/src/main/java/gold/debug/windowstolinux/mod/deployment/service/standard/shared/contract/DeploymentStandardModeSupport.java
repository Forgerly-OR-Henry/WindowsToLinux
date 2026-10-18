package gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract;

import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs.*;

/** Mode extension contract; shared code never constructs a mode implementation. / 模式扩展合同，共用代码不构造模式实现。 */
public interface DeploymentStandardModeSupport {
    /** Identifies whether the captured entry permits assisted extensions. / 标识已捕获入口是否允许辅助扩展。
     * @return classified original assisted publication result / 分类原辅助发布结果 */
    boolean assisted();

    /** Frozen task capabilities and original model routing revision. / 冻结任务能力及原模型路由修订。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param revision captured original model revision / 已捕获原模型修订 */
    record Scope(ModContext context, String revision) {
    }

    /** Captures the original task mode, approval policy and model revision. / 捕获原任务模式、审批策略及模型修订。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param task original admitted task / 原已准入任务
     * @param approval captured approval policy / 已捕获审批策略
     * @param remember captured exact-action reuse preference / 已捕获精确动作复用偏好
     * @return frozen capabilities and model revision / 冻结能力及模型修订
     * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    Scope freeze(ModContext context, TaskContext task, String approval, boolean remember) throws ApiException;

    /** Rebinds the original captured mode without creating another task. / 重新绑定原捕获模式，不另建任务。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param task original admitted task / 原已准入任务
     * @param revision captured original model revision / 已捕获原模型修订
     * @param approval captured approval policy / 已捕获审批策略
     * @param remember captured exact-action reuse preference / 已捕获精确动作复用偏好
     * @return capabilities rebound to the captured original mode / 重新绑定原捕获模式的能力
     * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    ModContext recover(ModContext context, TaskContext task, String revision, String approval, boolean remember) throws ApiException;

    /** Creates task-local mode adapters for the shared deterministic pipeline. / 为共用确定性流程建立任务所属的模式适配。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param validate deterministic review validator / 确定性审阅校验器
     * @return task-local mode extension adapter / 任务所属模式扩展适配 */
    Session session(ModContext context, java.util.function.BiConsumer<Prompt, Answers> validate);

    /** Task-local mode extension using shared deterministic contracts. / 使用共用确定性合同的任务所属模式扩展。 */
    interface Session {
        /** Observes the actual source snapshot selected after workflow admission; creates no authority. */
        default void sourceCaptured(ServerId server, DeploymentSourceMaterial material, TaskContext task) throws ApiException {
        }

        default gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request workspaceDemand(
            gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts facts,
            gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive archive, Answers answer,
            long availableBytes, DeploymentSourceMaterial material, TaskContext task) throws Exception {
            return answer.workspaceRequest().mode() == gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Mode.EXPLICIT
                ? answer.workspaceRequest()
                : gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.DeploymentStandardWorkspaceEstimate.estimate(facts);
        }

        /** Compares independently observed source units with deterministic discovery. / 比较独立观测的来源单元与确定性发现。
         * @param server exact original target server / 精确原目标服务器
         * @param browser bounded frozen source browser / 有界冻结来源浏览器
         * @param discovered components discovered from the frozen source / 从冻结来源发现的组件
         * @param task original admitted task / 原已准入任务
         * @return components selected from the verified source comparison / 从已核实来源比较选择的组件
         * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
        default List<DiscoveredProjectPart> compare(ServerId server, DeploymentSourceBrowser browser,
                                                    List<DiscoveredProjectPart> discovered, TaskContext task) throws Exception {
            return discovered;
        }

        /** Applies bounded technical suggestions to the original review prompt. / 将有界技术建议应用于原审阅问题。
         * @param server exact original target server / 精确原目标服务器
         * @param prompt original deterministic review prompt / 原确定性审阅问题
         * @param material original open frozen source handle / 原开放冻结来源句柄
         * @param task original admitted task / 原已准入任务
         * @return original form with accepted bounded suggestions / 带已接受有界建议的原表单
         * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
        default Prompt prompt(ServerId server, Prompt prompt, DeploymentSourceMaterial material,
                              TaskContext task) throws Exception {
            return prompt;
        }

        /** Collects exact component answers and retains independent host approval. / 收集精确组件答案并保留独立宿主审批。
         * @param server exact original target server / 精确原目标服务器
         * @param revision captured original model revision / 已捕获原模型修订
         * @param prompts complete original component review set / 完整原组件审阅集合
         * @param reviewer original human review adapter / 原人工审阅适配
         * @param task original admitted task / 原已准入任务
         * @return complete validated component answers or original refusal / 完整已校验组件答案或原拒绝
         * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
        OperationResult<Map<String, Answers>> review(ServerId server, String revision,
                                                     List<Prompt> prompts, Reviewer reviewer, TaskContext task) throws Exception;

        /** Binds bounded build correction to the reviewed component and source. / 将有界构建纠正绑定到已审阅组件及来源。
         * @param prompt original deterministic review prompt / 原确定性审阅问题
         * @param initial last deterministically accepted component answers / 上次确定性接受的组件答案
         * @param reviewer original human review adapter / 原人工审阅适配
         * @return bounded component correction adapter / 有界组件纠正适配 */
        default DeploymentAssistedBuildCorrection correction(Prompt prompt, Answers initial, Reviewer reviewer) {
            throw new IllegalStateException("Static mode has no build correction");
        }

        /** Routes the original publication through its captured mode. / 按已捕获模式路由原发布。
         * @param ready classified result from deterministic publication preparation / 确定性发布准备的分类结果
         * @return original classified publication preparation or recovery route / 原分类发布准备或恢复路由 */
        default OperationResult<DeploymentRefFlow.PreparedPublish> publication(OperationResult<DeploymentRefFlow.PreparedPublish> ready) {
            return ready;
        }
    }
}

