package gold.debug.windowstolinux.shared.model.capability;

/** A fixed language or build-tool capability whose observed version may gate deployment. / 可用观测版本约束部署的固定语言或构建工具能力。 */
public enum EcosystemToolType {
    /** Bundler. / Bundler。 */ BUNDLER,
    /** Cargo. / Cargo。 */ CARGO,
    /** C compiler. / C 编译器。 */ C_COMPILER,
    /** CMake. / CMake。 */ CMAKE,
    /** Composer. / Composer。 */ COMPOSER,
    /** C++ compiler. / C++ 编译器。 */ CPP_COMPILER,
    /** .NET SDK. / .NET SDK。 */ DOTNET,
    /** Go toolchain. / Go 工具链。 */ GO,
    /** JAR archiver. / JAR 归档器。 */ JAR,
    /** Java runtime. / Java 运行时。 */ JAVA,
    /** Java compiler. / Java 编译器。 */ JAVAC,
    /** Kotlin compiler. / Kotlin 编译器。 */ KOTLINC,
    /** Maven. / Maven。 */ MAVEN,
    /** Node.js. / Node.js。 */ NODE,
    /** npm. / npm。 */ NPM,
    /** Ninja build system. / Ninja 构建系统。 */ NINJA,
    /** PHP CLI. / PHP CLI。 */ PHP,
    /** pip. / pip。 */ PIP,
    /** Pipenv. / Pipenv。 */ PIPENV,
    /** pnpm. / pnpm。 */ PNPM,
    /** Poetry. / Poetry。 */ POETRY,
    /** Python. / Python。 */ PYTHON,
    /** Ruby. / Ruby。 */ RUBY,
    /** Rust compiler. / Rust 编译器。 */ RUSTC,
    /** uv. / uv。 */ UV,
    /** Yarn. / Yarn。 */ YARN
}
