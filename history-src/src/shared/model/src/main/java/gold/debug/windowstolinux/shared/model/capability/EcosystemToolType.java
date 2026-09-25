package gold.debug.windowstolinux.shared.model.capability;

/**
 * A fixed language or build-tool capability whose observed version may gate deployment. / 可用观测版本约束部署的固定语言或构建工具能力。
 */
public enum EcosystemToolType {
    /**
     * BUNDLER classification within ecosystem tool type.
     * <p>生态工具类型中的BUNDLER分类。
     */
    BUNDLER,
    /**
     * CARGO classification within ecosystem tool type.
     * <p>生态工具类型中的CARGO分类。
     */
    CARGO,
    /**
     * C compiler. / C 编译器。
     */
    C_COMPILER,
    /**
     * CMAKE classification within ecosystem tool type.
     * <p>生态工具类型中的CMAKE分类。
     */
    CMAKE,
    /**
     * COMPOSER classification within ecosystem tool type.
     * <p>生态工具类型中的COMPOSER分类。
     */
    COMPOSER,
    /**
     * C++ compiler. / C++ 编译器。
     */
    CPP_COMPILER,
    /**
     * DOTNET classification within ecosystem tool type.
     * <p>生态工具类型中的DOTNET分类。
     */
    DOTNET,
    /**
     * Go toolchain. / Go 工具链。
     */
    GO,
    /**
     * JAR archiver. / JAR 归档器。
     */
    JAR,
    /**
     * Java runtime. / Java 运行时。
     */
    JAVA,
    /**
     * Java compiler. / Java 编译器。
     */
    JAVAC,
    /**
     * Kotlin compiler. / Kotlin 编译器。
     */
    KOTLINC,
    /**
     * MAVEN classification within ecosystem tool type.
     * <p>生态工具类型中的MAVEN分类。
     */
    MAVEN,
    /**
     * NODE classification within ecosystem tool type.
     * <p>生态工具类型中的节点分类。
     */
    NODE,
    /**
     * NPM classification within ecosystem tool type.
     * <p>生态工具类型中的NPM分类。
     */
    NPM,
    /**
     * Ninja build system. / Ninja 构建系统。
     */
    NINJA,
    /**
     * PHP classification within ecosystem tool type.
     * <p>生态工具类型中的PHP分类。
     */
    PHP,
    /**
     * PIP classification within ecosystem tool type.
     * <p>生态工具类型中的PIP分类。
     */
    PIP,
    /**
     * PIPENV classification within ecosystem tool type.
     * <p>生态工具类型中的PIPENV分类。
     */
    PIPENV,
    /**
     * PNPM classification within ecosystem tool type.
     * <p>生态工具类型中的PNPM分类。
     */
    PNPM,
    /**
     * POETRY classification within ecosystem tool type.
     * <p>生态工具类型中的POETRY分类。
     */
    POETRY,
    /**
     * PYTHON classification within ecosystem tool type.
     * <p>生态工具类型中的PYTHON分类。
     */
    PYTHON,
    /**
     * RUBY classification within ecosystem tool type.
     * <p>生态工具类型中的Ruby分类。
     */
    RUBY,
    /**
     * Rust compiler. / Rust 编译器。
     */
    RUSTC,
    /**
     * UV classification within ecosystem tool type.
     * <p>生态工具类型中的UV分类。
     */
    UV,
    /**
     * YARN classification within ecosystem tool type.
     * <p>生态工具类型中的YARN分类。
     */
    YARN
}
