package gold.debug.windowstolinux.shared.agent.tool;

import java.util.*;

import gold.debug.windowstolinux.shared.deploy.delivery.ManagedDelivery;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.linux.workspace.RemoteProjectPort;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;

/** Application-owned credentials and managed publication, with no UI or database access in the engine. / 由应用持有凭据及受管发布，引擎不访问界面或数据库。 */
public interface AgentDeliveryPort {
    /** Reads current host evidence. / 读取当前主机证据。
     * @return observed nonsecret facts / 观察到的非秘密事实
     * @throws Exception on unavailable observations / 观察不可用时
     */
    Map<String, String> inspect() throws Exception;

    /** Uploads the frozen snapshot and creates a task-owned protected source copy. / 上传冻结快照并创建任务所属受保护源码副本。
     * @param component declared component / 已声明组件
     * @return exact candidate and initial revision / 精确候选及初始修订
     * @throws Exception on preparation failure / 准备失败时
     */
    Prepared prepare(ManagedDelivery.Component component) throws Exception;

    /** Returns mechanical project operations. / 返回机械项目操作。
     * @return scoped remote project port / 限定作用域远端项目端口
     */
    RemoteProjectPort projects();

    /** Revalidates and publishes all components in dependency order. / 重新校验并按依赖顺序发布全部组件。
     * @param delivery validated component graph / 已校验组件图
     * @param candidates exact prepared candidates / 精确已准备候选
     * @param revisions current source revisions / 当前源码修订
     * @param artifacts verified sealed artifact digests / 已验证封存制品摘要
     * @return actual managed publication and recovery outcome / 实际受管发布及恢复结果
     * @throws Exception on unavailable or unknown execution / 执行不可用或未知时
     */
    Result deliver(ManagedDelivery delivery, Map<String, Prepared> candidates, Map<String, String> revisions,
            Map<String, String> artifacts) throws Exception;
    /** A task-owned source candidate. / 任务所属源码候选。
     * @param workspace uploaded workspace / 已上传工作区
     * @param revision source identity / 源码身份
     */
    record Prepared(RemoteWorkspace workspace, String revision) {
    }

    /** Actual publication result, never a model assertion. / 实际发布结果，不是模型断言。
     * @param status managed transaction status / 受管事务状态
     * @param healthy verified required component identities / 已验证必需组件身份
     * @param evidence bounded observation summary / 有界观察摘要
     */
    record Result(DeploymentStatus status, Set<String> healthy, String evidence) {
        /** Freezes actual health identities. / 冻结实际健康身份。
         * @param status transaction result / 事务结果
         * @param healthy verified components / 已验证组件
         * @param evidence observations / 观察
         */
        public Result {
            Objects.requireNonNull(status);
            healthy = Set.copyOf(healthy);
            Objects.requireNonNull(evidence);
        }
    }
}
