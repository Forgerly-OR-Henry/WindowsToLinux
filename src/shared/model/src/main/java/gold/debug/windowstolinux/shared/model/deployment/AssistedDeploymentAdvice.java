package gold.debug.windowstolinux.shared.model.deployment;

import java.util.*;

/**
 * Evidence-linked suggestions; values remain subject to deterministic validation. / 关联证据的建议，值仍须确定性验证。
 * @param summary bounded explanation / 有界解释
 * @param suggestions candidate values with references / 带引用候选值
 * @param unresolved unresolved issues requiring human review / 需要人工审阅的未解决问题
 */
public record AssistedDeploymentAdvice(String summary, List<Candidate> suggestions, List<String> unresolved) {
    /** Freezes bounded advice without authorizing an operation. / 冻结有界建议，不授权操作。
     * @param summary bounded explanation / 有界解释
     * @param suggestions candidate values with references / 带引用候选值
     * @param unresolved unresolved issues requiring human review / 需要人工审阅的未解决问题
     */
    public AssistedDeploymentAdvice {
        if (summary == null || summary.length() > 2048)
            throw new IllegalArgumentException("invalid assisted summary");
        suggestions = List.copyOf(suggestions);
        unresolved = List.copyOf(unresolved);
        if (suggestions.size() > 64 || unresolved.size() > 64 || unresolved.stream().anyMatch(v -> v.length() > 1024))
            throw new IllegalArgumentException("assisted advice too large");
    }
    /**
     * One nonsecret candidate with observed evidence references. / 带观察证据引用的一个非秘密候选值。
     * @param field requested field identity / 请求字段身份
     * @param candidate supplied candidate value / 提供的候选值
     * @param evidence observed reference identifiers / 观察引用标识
     */
    public record Candidate(String field, String candidate, List<String> evidence) {
        /** Requires bounded suggestion fields. / 要求有界建议字段。
        * @param field requested field identity / 请求字段身份
        * @param candidate supplied candidate value / 提供的候选值
        * @param evidence observed reference identifiers / 观察引用标识
        */
        public Candidate {
            if (field == null || candidate == null || field.length() > 160 || candidate.length() > 4096)
                throw new IllegalArgumentException("invalid candidate");
            evidence = List.copyOf(evidence);
            if (evidence.isEmpty() || evidence.size() > 64 || evidence.stream().anyMatch(v -> v.length() > 160))
                throw new IllegalArgumentException("invalid candidate evidence");
        }
    }
}
