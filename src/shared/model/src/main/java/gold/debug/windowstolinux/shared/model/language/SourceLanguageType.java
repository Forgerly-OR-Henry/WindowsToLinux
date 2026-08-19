package gold.debug.windowstolinux.shared.model.language;

/**
 * A source language proven by a bounded file path or metadata entry.
 *
 * <p>由有界文件路径或元数据条目证实的源码语言。
 */
public enum SourceLanguageType {
    /** No bounded language identity. / 无有界语言身份。 */ UNKNOWN,
    /** Java source. / Java 源码。 */ JAVA,
    /** JavaScript source. / JavaScript 源码。 */ JAVASCRIPT,
    /** TypeScript source. / TypeScript 源码。 */ TYPESCRIPT,
    /** Python source. / Python 源码。 */ PYTHON,
    /** HTML or static web source. / HTML 或静态网页源码。 */ HTML,
    /** Dockerfile or Containerfile source. / Dockerfile 或 Containerfile 源码。 */ CONTAINERFILE,
    /** Go source. / Go 源码。 */ GO,
    /** Rust source. / Rust 源码。 */ RUST,
    /** C# source. / C# 源码。 */ CSHARP,
    /** Kotlin source. / Kotlin 源码。 */ KOTLIN,
    /** PHP source. / PHP 源码。 */ PHP,
    /** Ruby source. / Ruby 源码。 */ RUBY,
    /** C source. / C 源码。 */ C,
    /** C++ source. / C++ 源码。 */ CPP,
    /** Scala source. / Scala 源码。 */ SCALA,
    /** Clojure source. / Clojure 源码。 */ CLOJURE,
    /** Elixir source. / Elixir 源码。 */ ELIXIR,
    /** Dart source. / Dart 源码。 */ DART,
    /** Lua source. / Lua 源码。 */ LUA,
    /** Perl source. / Perl 源码。 */ PERL,
    /** Swift source. / Swift 源码。 */ SWIFT,
    /** Shell source, which never creates an arbitrary command path. / Shell 源码，绝不创建任意命令路径。 */ SHELL
}
