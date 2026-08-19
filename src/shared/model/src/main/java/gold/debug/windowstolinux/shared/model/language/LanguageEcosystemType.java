package gold.debug.windowstolinux.shared.model.language;

/**
 * A deterministically observed language ecosystem without a primary-language ranking.
 *
 * <p>不含主要语言排序的确定性语言生态观测。
 */
public enum LanguageEcosystemType {
    /** Java and JVM build metadata. / Java 与 JVM 构建元数据。 */
    JAVA,
    /** Node.js package and source metadata. / Node.js 包与源码元数据。 */
    NODE_JS,
    /** Python project and source metadata. / Python 项目与源码元数据。 */
    PYTHON,
    /** Go modules and source. / Go 模块与源码。 */ GO,
    /** Rust Cargo projects and source. / Rust Cargo 项目与源码。 */ RUST,
    /** .NET projects and source. / .NET 项目与源码。 */ DOTNET,
    /** Kotlin/JVM projects and source. / Kotlin/JVM 项目与源码。 */ KOTLIN,
    /** PHP Composer projects and source. / PHP Composer 项目与源码。 */ PHP,
    /** Ruby Bundler projects and source. / Ruby Bundler 项目与源码。 */ RUBY,
    /** C and C++ projects. / C 与 C++ 项目。 */ NATIVE,
    /** Other VM or functional-language projects. / 其他虚拟机或函数式语言项目。 */ ALTERNATIVE_VM,
    /** Script-language projects that remain recognition-only. / 保持仅识别的脚本语言项目。 */ SCRIPT,
    /** Swift package projects. / Swift Package 项目。 */ SWIFT
}
