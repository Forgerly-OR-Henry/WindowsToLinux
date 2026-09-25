package gold.debug.windowstolinux.shared.standard.deploy.assistance;

import java.util.Objects;
import java.util.Set;

import gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice;

/** One closed read-only proposal or completed analysis. / 一个封闭的只读提案或完成的分析。
 * @param action LIST, READ, SEARCH or ADVISE / 列目录、读取、搜索或建议
 * @param sourceRevision exact observed snapshot / 精确观察快照
 * @param argument relative path or literal search query / 相对路径或字面搜索内容
 * @param offset bounded source page offset / 有界源码分页偏移
 * @param limit bounded source page size / 有界源码分页大小
 * @param advice final evidence-linked result / 最终关联证据的结果
 */
public record AssistedAnalysisDecision(String action, String sourceRevision, String argument, int offset, int limit,
        AssistedDeploymentAdvice advice) {
    /** Rejects execution tools and malformed source requests. / 拒绝执行工具及畸形源码请求。
     * @param action closed operation name / 封闭操作名称
     * @param sourceRevision snapshot identity / 快照身份
     * @param argument source argument / 源码参数
     * @param offset page offset / 分页偏移
     * @param limit page size / 分页大小
     * @param advice final result / 最终结果
     */
    public AssistedAnalysisDecision {
        if (!Set.of("LIST", "READ", "SEARCH", "ADVISE").contains(action))
            throw new IllegalArgumentException("unsupported analysis action");
        Objects.requireNonNull(sourceRevision);
        Objects.requireNonNull(argument);
        Objects.requireNonNull(advice);
        if (argument.length() > 500 || offset < 0 || offset > 1000000 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("invalid analysis page");
        if (!action.equals("ADVISE") && (!advice.suggestions().isEmpty() || !advice.unresolved().isEmpty()))
            throw new IllegalArgumentException("source query cannot carry advice");
    }
}
