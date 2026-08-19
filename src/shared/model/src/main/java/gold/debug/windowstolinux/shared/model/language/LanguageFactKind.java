package gold.debug.windowstolinux.shared.model.language;

/**
 * A stable deterministic language-related value that may be used to prefill a review form.
 *
 * <p>可用于预填审阅表单的稳定确定性语言相关值。
 */
public enum LanguageFactKind {
    /** Java major version. / Java 主版本。 */ JAVA_VERSION,
    /** Java binary main class. / Java 二进制主类。 */ JAVA_MAIN_CLASS,
    /** Node.js major version. / Node.js 主版本。 */ NODE_MAJOR_VERSION,
    /** Python minor version. / Python 次版本。 */ PYTHON_VERSION,
    /** Python module entrypoint. / Python 模块入口。 */ PYTHON_ENTRYPOINT
}
