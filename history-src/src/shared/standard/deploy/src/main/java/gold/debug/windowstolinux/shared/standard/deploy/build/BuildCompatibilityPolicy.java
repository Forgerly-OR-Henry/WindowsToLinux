package gold.debug.windowstolinux.shared.standard.deploy.build;

/**
 * Classifies build output for bounded toolchain retries. / 根据构建输出判定有界工具链重试。
 */
final class BuildCompatibilityPolicy {
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
        return diagnostic != null && (diagnostic.contains("BUILD_COMPATIBILITY=toolchain")
                || diagnostic.contains("Unsupported class file major version")
                || diagnostic.contains("invalid source release:") || diagnostic.contains("invalid target release:")
                || diagnostic.contains("requires rustc ") || diagnostic.contains("requires go >=")
                || diagnostic.contains("NETSDK1045") || diagnostic.contains("EBADENGINE")
                || diagnostic.contains("The engine \"node\" is incompatible")
                || diagnostic.contains("requires a different Python")
                || diagnostic.contains("Unknown Kotlin JVM target:")
                || diagnostic.contains("Unknown JVM target version:")
                || diagnostic.contains("Your Ruby version is") && diagnostic.contains("but your Gemfile specified")
                || diagnostic.contains("your php version") && diagnostic.contains("does not satisfy"));
    }
}
