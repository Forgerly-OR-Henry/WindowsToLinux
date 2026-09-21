package gold.debug.windowstolinux.app.service.contract;

import gold.debug.windowstolinux.app.service.contract.definition.RecoverySnapshot;
import gold.debug.windowstolinux.shared.model.recovery.TerminalTarget;
import java.util.List;

/**
 * Controls one rescue without exposing browser or persistence internals. / 控制一次救援，不暴露浏览器和持久化内部对象。
 */
public interface SshRecoverySession extends AutoCloseable {
    /**
     * Reads the current non-observation status. / 读取不含观察原文的当前状态。
     *
     * @return the current non-observation status / 不含观察原文的当前状态
     */
    RecoverySnapshot snapshot();
    /**
     * Lists selectable terminals after manual login. / 人工登录后列出可选择终端。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    List<TerminalTarget> terminals();
    /**
     * Confirms server identity, observation consent and completed manual handoff. / 确认服务器身份、观察授权及人工交接完成。
     *
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param observationConsent observation consent / 观测同意
     * @param priorActionReconciled prior action reconciled / 此前动作已核对
     */
    void bindTerminal(String target, String serverId, boolean observationConsent, boolean priorActionReconciled);
    /**
     * Approves the exact pending action; high-impact commands need separate approval. / 批准确切的待执行动作，高影响命令需要独立批准。
     *
     * @param token token / 令牌
     * @param highImpactApproved high impact approved / 高影响已批准
     */
    void confirmAction(String token, boolean highImpactApproved);
    /**
     * Revokes proposals and observation while the user takes over. / 人工接管时撤销建议并停止观察。
     */
    void pause();
    /**
     * Resets the active budget and requires a fresh terminal handoff. / 重置主动执行预算并要求重新交接终端。
     */
    void resume();
    /**
     * Ends the session without replaying or claiming cancellation of remote commands. / 结束会话，不重放命令或宣称远端命令已取消。
     */
    @Override void close();
}
