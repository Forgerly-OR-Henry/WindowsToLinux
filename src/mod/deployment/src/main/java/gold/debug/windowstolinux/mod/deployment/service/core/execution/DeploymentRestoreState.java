package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import static gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentRestoreEvidence.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.api.*;

/** Original restore journal cursor with one authenticated task. / 单项已认证任务的原恢复账本游标。 */
final class DeploymentRestoreState {
    /** Original authorized journal and compare-and-set boundary. / 原已授权账本及比较更新边界。 */
    private final DeploymentHandoffLedger ledger;

    /** Latest confirmed revision of the same handoff. / 同一交接的最新已确证修订。 */
    DeploymentHandoffLedger.Entry entry;

    /** Mutable private evidence saved before each attempted effect. / 每次尝试副作用前保存的可变私有依据。 */
    final ObjectNode facts;

    /** Authentic original root context retained by the caller. / 调用方保留的真实原根上下文。 */
    final TaskContext task;
    /** Copies evidence without transferring root-resource ownership. / 复制依据而不转移根资源归属。
     * @param ledger original provider journal / 原提供方账本
     * @param entry authorized original row / 已授权原行
     * @param task authentic original task / 真实原任务 */
    DeploymentRestoreState(DeploymentHandoffLedger ledger, DeploymentHandoffLedger.Entry entry, TaskContext task) {
        this.ledger = ledger;
        this.entry = entry;
        this.task = task;
        facts = ledger.recovery(entry);
    }

    /** Selects one component's original recovery facts. / 选择单组件原恢复事实。
     * @param component original component ID / 原组件ID
     * @return mutable component facts / 可变组件事实 */
    ObjectNode unit(String component) {
        return facts.withObject("components").withObject(component);
    }

    /** Advances only the expected journal revision and reloads committed evidence. / 仅推进预期账本修订并重读已提交依据。
     * @param state confirmed next phase / 已确证下一阶段
     * @throws ApiException if original ownership or persistence is unavailable / 原归属或持久化不可用时 */
    void save(DeploymentHandoffState state) throws ApiException {
        ledger.advance(entry, entry.publicValue().state(), state, facts, entry.result(), task);
        entry = ledger.find(entry.publicValue().id(), task)
                .orElseThrow(() -> new ApiException(problem(ProblemCode.UNKNOWN, "DEPLOYMENT_RESTORE_JOURNAL_ABSENT")));
    }

    /** Persists an explicit blocker without concluding the root task. / 持久保存明确阻碍而不结束根任务。
     * @param <T> result payload type / 结果载荷类型
     * @param event original uncertainty event / 原未知事件
     * @return retained unknown result / 留存未知结果
     * @throws ApiException if the original journal cannot be saved / 原账本无法保存时 */
    <T> OperationResult<T> uncertain(String event) throws ApiException {
        facts.putArray("blockers").add(event);
        save(DeploymentHandoffState.UNKNOWN);
        return unknown(event);
    }

    /** Reloads after a call or close failure and preserves its original terminal facts. / 调用或关闭失败后重读并保留原终态事实。
     * @param <T> result payload type / 结果载荷类型
     * @param event original uncertainty event / 原未知事件
     * @return retained unknown result / 留存未知结果
     * @throws ApiException if original evidence cannot be loaded or saved / 原依据无法加载或保存时 */
    <T> OperationResult<T> retain(String event) throws ApiException {
        entry = ledger.find(entry.publicValue().id(), task)
                .orElseThrow(() -> new ApiException(problem(ProblemCode.UNKNOWN, "DEPLOYMENT_RESTORE_JOURNAL_ABSENT")));
        return entry.publicValue().state() != DeploymentHandoffState.FINISHED ? uncertain(event) : unknown(event);
    }
}
