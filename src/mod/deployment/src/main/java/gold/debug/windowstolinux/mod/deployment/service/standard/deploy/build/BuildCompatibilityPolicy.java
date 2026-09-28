package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build;

/**
 * Classifies build output for bounded toolchain retries. / 根据构建输出判定有界工具链重试。
 */
final class BuildCompatibilityPolicy {
    /** Exact compatibility phrases, shared with the streaming remote summarizer. / 与远端流式摘要器共用的精确兼容性短语。 */
    private static final java.util.List<java.util.List<String>> GROUPS = java.util.List.of(
            java.util.List.of("BUILD_COMPATIBILITY=toolchain"),
            java.util.List.of("Unsupported class file major version"), java.util.List.of("invalid source release:"),
            java.util.List.of("invalid target release:"), java.util.List.of("requires rustc "),
            java.util.List.of("requires go >="), java.util.List.of("NETSDK1045"), java.util.List.of("EBADENGINE"),
            java.util.List.of("The engine \"node\" is incompatible"), java.util.List.of("requires a different Python"),
            java.util.List.of("Unknown Kotlin JVM target:"), java.util.List.of("Unknown JVM target version:"),
            java.util.List.of("Your Ruby version is", "but your Gemfile specified"),
            java.util.List.of("your php version", "does not satisfy"));
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private BuildCompatibilityPolicy() {
    }

    /**
     * Only explicit compatibility diagnostics allow the second branch. / 仅明确的兼容性诊断允许切换下一候选分支。
     *
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return true when only explicit compatibility diagnostics allow the second branch, false otherwise / 仅明确的兼容性诊断允许切换下一候选分支时为 true，否则为 false
     */
    static boolean retryable(String diagnostic) {
        return diagnostic != null && GROUPS.stream().anyMatch(group -> group.stream().allMatch(diagnostic::contains));
    }

    /** Exposes the same phrases to the bounded remote scanner. / 向有界远端扫描器提供同一组短语。
     * @return immutable disjunction of conjunctions / 不可变的合取组析取 */
    static java.util.List<java.util.List<String>> groups() {
        return GROUPS;
    }
}
