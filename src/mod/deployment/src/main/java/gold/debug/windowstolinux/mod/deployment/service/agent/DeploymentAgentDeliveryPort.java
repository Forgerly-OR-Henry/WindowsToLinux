package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;

/** Narrow real remote tools and common managed publication for the independent Agent engine. / 供独立自主引擎使用的窄真实远端工具与共用受管发布能力。 */
public interface DeploymentAgentDeliveryPort extends AutoCloseable {
    /** Actual task-owned source candidate. / 真实任务所属来源候选。
     * @param workspace exact remote workspace / 精确远端工作区
     * @param revision observed source revision / 已观察来源修订 */
    record Prepared(DeploymentRemoteWorkspace workspace, String revision) {
    }

    /** Actual bounded command feedback. / 真实有界命令反馈。
     * @param known whether the effect and result are known / 副作用和结果是否已知
     * @param succeeded whether exit was successful / 退出是否成功
     * @param exitStatus observed exit code / 已观察退出码
     * @param evidenceOutput bounded redacted output / 有界脱敏输出 */
    record CommandFeedback(boolean known, boolean succeeded, int exitStatus, String evidenceOutput) {
    }

    /** Actual publication result from common health and rollback. / 来自共用健康与回滚的真实发布结果。
     * @param healthy exact verified components / 精确已核实组件
     * @param evidence bounded nonsecret observation / 有界不含秘密观察 */
    record Published(Set<String> healthy, String evidence) {
        /** Copies verified identities. / 复制已核实身份。
         * @param healthy verified components / 已核实组件
         * @param evidence observation / 观察 */
        public Published {
            healthy = Set.copyOf(healthy);
        }
    }

    /** Observes current authenticated host capabilities. / 观察当前已认证主机能力。
     * @return nonsecret actual facts / 不含秘密的真实事实
     * @throws Exception if observation is unavailable / 观察不可用时 */
    Map<String, String> inspect() throws Exception;

    /** Uploads original frozen source and opens one protected helper workspace. / 上传原冻结来源并开启一个受保护 helper 工作区。
     * @param applicationId reviewed application identity / 已审应用身份
     * @param component declared component / 已声明组件
     * @return observed candidate and revision / 已观察候选与修订
     * @throws Exception on unknown or denied preparation / 准备未知或被拒绝时 */
    Prepared prepare(String applicationId, DeploymentAgentPlan.Component component) throws Exception;

    /** Executes exact reviewed script in the protected candidate. / 在受保护候选中执行精确已审脚本。
     * @param task original task / 原任务
     * @param candidate actual candidate / 实际候选
     * @param revision current source revision / 当前源码修订
     * @param script exact script / 精确脚本
     * @param timeout execution deadline / 执行期限
     * @return actual bounded result / 真实有界结果
     * @throws Exception on denied or unknown command / 命令被拒绝或未知时 */
    CommandFeedback command(TaskContext task, Prepared candidate, String revision, String script, Duration timeout)
        throws Exception;

    /** Reads only current protected task source. / 仅读取当前受保护任务源码。
     * @param task original task / 原任务
     * @param candidate actual candidate / 实际候选
     * @param revision current source revision / 当前源码修订
     * @param path relative file / 相对文件
     * @param offset line cursor / 行游标
     * @param limit line count / 行数
     * @return bounded redacted lines / 有界脱敏行
     * @throws Exception on unavailable reading / 读取不可用时 */
    String read(TaskContext task, Prepared candidate, String revision, String path, int offset, int limit)
        throws Exception;

    /** Applies a separately reviewed exact source patch and returns a new revision. / 应用单独审核的精确源码补丁并返回新修订。
     * @param task original task / 原任务
     * @param candidate actual candidate / 实际候选
     * @param revision expected source revision / 预期源码修订
     * @param patch exact reviewed structured patch / 精确已审结构化补丁
     * @return observed new source revision / 已观察新来源修订
     * @throws Exception on denied, conflicting or unknown patch / 补丁被拒绝、冲突或未知时 */
    String patch(TaskContext task, Prepared candidate, String revision, DeploymentAgentPatch patch) throws Exception;

    /** Seals actual build output for its current source revision. / 按当前源码修订封存真实构建输出。
     * @param task original task / 原任务
     * @param candidate actual candidate / 实际候选
     * @param revision current source revision / 当前源码修订
     * @return observed artifact digest / 已观察制品摘要
     * @throws Exception when sealing cannot be verified / 封存无法核实时 */
    String seal(TaskContext task, Prepared candidate, String revision) throws Exception;

    /** Revalidates and publishes the complete graph with common health and rollback. / 经共用健康和回滚复核并发布完整图。
     * @param task original task / 原任务
     * @param plan validated graph / 已校验图
     * @param candidates exact prepared candidates / 精确已准备候选
     * @param revisions current source revisions / 当前来源修订
     * @param artifacts verified sealed artifacts / 已核实封存制品
     * @return actual result or retained uncertainty / 真实结果或保留的不确定性
     * @throws Exception on unavailable delivery evidence / 交付依据不可用时 */
    OperationResult<Published> deliver(TaskContext task, DeploymentAgentPlan plan, Map<String, Prepared> candidates,
                                       Map<String, String> revisions, Map<String, String> artifacts) throws Exception;

    /** Rebinds all actual sends after an explicitly authorized paused model replacement. / 明确授权暂停模型替换后重新绑定全部真实发送。
     * @param revision newly verified whole-route revision / 新核实整路由修订
     * @throws Exception if this tool cannot safely replace its original authority / 此工具不能安全替换原权限时 */
    default void replaceConfiguration(String revision) throws Exception {
        throw new UnsupportedOperationException("Tool does not support explicit model replacement");
    }

    /** Releases only verified task-owned resources. / 仅释放已核实任务所属资源。
     * @throws Exception if release is unconfirmed / 释放无法确认时 */
    @Override
    void close() throws Exception;
}
