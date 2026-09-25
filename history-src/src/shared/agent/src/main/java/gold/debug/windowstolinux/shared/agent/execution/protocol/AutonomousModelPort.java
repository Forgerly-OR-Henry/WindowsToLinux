package gold.debug.windowstolinux.shared.agent.execution.protocol;

import java.util.*;

/** Purpose-isolated serial deployment reasoning. / 用途隔离的串行部署推理。 */
public interface AutonomousModelPort {
    /** Chooses a tool using actual observations and current revisions. / 根据实际观察及当前修订选择工具。
     * @param context bounded verified task state / 有界已验证任务状态
     * @param history recent actual observations / 最近实际观察
     * @param remaining task-wide budget / 任务全局预算
     * @return strict tool proposal / 严格工具提议
     * @throws Exception when the model or protocol fails / 模型或协议失败时
     */
    AgentProposal decide(Map<String, Object> context, List<Map<String, String>> history, int remaining)
            throws Exception;

    /** Advances only the deployment model list. / 仅推进部署模型列表。
     * @return whether a later model is available / 是否有后续模型可用
     */
    boolean advance();
}
