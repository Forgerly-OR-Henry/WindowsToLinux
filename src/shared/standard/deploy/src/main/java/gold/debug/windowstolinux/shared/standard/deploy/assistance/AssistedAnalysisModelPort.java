package gold.debug.windowstolinux.shared.standard.deploy.assistance;

import java.util.List;
import java.util.Map;

/** Analysis-only model boundary, without execution capabilities. / 不包含执行能力的分析模型边界。 */
@FunctionalInterface
public interface AssistedAnalysisModelPort {
    /** Requests a bounded source query or final evidence-linked advice. / 请求有界源码查询或最终关联证据的建议。
     * @param context immutable source and field context / 不可变源码及字段上下文
     * @param observations actual source observations / 实际源码观察
     * @param remaining task-wide remaining decisions / 任务剩余决策数
     * @return checked analysis decision / 已检查分析决定
     * @throws Exception when the model is unavailable or invalid / 模型不可用或无效时
     */
    AssistedAnalysisDecision analyze(Map<String, Object> context, List<Map<String, String>> observations, int remaining)
            throws Exception;
}
