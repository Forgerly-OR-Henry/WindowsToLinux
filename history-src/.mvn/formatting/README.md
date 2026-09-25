# 全仓格式工具

本目录保存开发工具版本、格式规则及统一入口，不属于应用运行依赖。默认入口面向本项目的 Windows 开发环境，不修改全局 Git、IDE 或系统配置。

## 准备与执行

在 `PATH` 配置 JDK 21、Maven 3.9、Python 3.12、Node.js 24、Go 1.26.8、rustfmt 1.9.0 和 .NET SDK 10.0.300。先显式执行 `python .mvn/formatting/bootstrap.py`，将固定工具装入本目录已被 Git 忽略的 `target/` 和 `node_modules/`。Maven 格式插件首次使用按 Maven 的既有仓库配置获取依赖。

```powershell
python .mvn/formatting/bootstrap.py
python .mvn/formatting/format.py --write
python .mvn/formatting/format.py --check
mvn clean verify
```

`--write` 才写入源码；`--check` 不隐式安装或修复，工具缺失、执行失败或格式不符均返回非零，并检查维护文件的摘要未发生变化。可用 `--only java|web|python|kotlin|shell|native|csharp|go|rust|php|ruby|basic` 聚焦一类。前端 `npm run format:check` 使用同一 Web/文本规则。Java/Groovy 检查随 Maven `verify` 执行。

## 固定工具与范围

| 范围                               | 工具 / 版本                             | 配置与限制                                                                |
| ---------------------------------- | --------------------------------------- | ------------------------------------------------------------------------- |
| Java                               | Spotless 2.44.3 / Eclipse JDT 4.32      | 四空格、120 列、导入分组、包与导入空行；不格式化注释文本                  |
| Groovy Gradle                      | Spotless / Groovy Eclipse 4.26          | 四空格，只格式化，不开启语义修复                                          |
| Web、JSON、YAML、Markdown、XML/SVG | Prettier 3.6.2 / XML 插件 3.4.2         | 两空格；Vue 内嵌代码启用格式化；保留 HTML/SVG 文本空白，配置 XML 统一缩进 |
| Python                             | Ruff 0.11.13                            | 四空格、保持引号风格，只执行 formatter                                    |
| Kotlin                             | ktfmt 0.54                              | Kotlin 官方四空格风格                                                     |
| Shell                              | shfmt 3.12.0                            | 四空格；不使用语义简化；保留 heredoc                                      |
| C/C++                              | clang-format 20.1.8                     | LLVM 基础、四空格、不排序 include、不改写注释                             |
| C#                                 | dotnet format whitespace / SDK 10.0.300 | 仅空白检查，无分析器自动修复                                              |
| Go / go.mod                        | gofmt / Go 1.26.8                       | Go 的必要制表符；`go mod edit -print` 只读取并生成格式化文本              |
| Rust                               | rustfmt 1.9.0                           | 使用各样例 Cargo.toml 声明的 edition                                      |
| PHP                                | PHP-CS-Fixer 3.82.1 / PHP 8.4.25        | 仅 php.php 登记的空白规则，禁止 risky 修复                                |
| Ruby / Gemfile                     | RuboCop 1.68.0 / Ruby 3.4.4             | 只启用 Layout，安全自动修复；依赖版本固定在 tools.json                    |
| 其余配置、CMake、声明文件          | 内置文件边界规范化                      | UTF-8、末尾换行、LF；批处理 CRLF，不清理可能属于值的行尾空白              |

可下载二进制的固定 URL 和 SHA-256 位于 `tools.json`；Python 依赖版本位于 `requirements.txt`，npm 开发依赖锁定于 `package-lock.json`。缓存可删除后重建，不提交，也不随本次验证临时文件清理。

## 例外及片段

`exceptions.json` 明确登记锁文件、第三方 Maven wrapper、图标许可证、二进制、构建产物、缓存和保留证据。业务 CSV、文本和日志样例按具体路径登记，保持精确字节；不排除整个测试目录。新增异常语法或特殊空白载荷须登记具体文件及理由。

`centos-source-repositories.py` 是八空格缩进的 Python 插入片段，先去公共缩进再经 Ruff 格式化，最后恢复。`10-native-instances.sh` 与 `20-native-targets.sh` 共用一个 Python heredoc，合并交给 shfmt 后按内部临时标记拆分，标记不会写入资源。不得将这些片段误当独立脚本改写，或排除整个 helper。

格式修改须保持字面量、Java 文本块、Shell heredoc、正则、协议和黄金输入的运行含义。Vue 单文件组件开启内嵌格式化，使脚本和模板表达式遵循同一规则；其他文件关闭内嵌语言格式化。helper 内容变化后重新计算 `ManagedHelperBundle.EXPECTED_SHA256`，执行 Java/Python/Bash 检查；纯格式变化不提升协议版本。提交前运行全仓检查，并比较两次 `--write` 之间的 Git 维护文件摘要，要求零差异。

工具用法依据：[Spotless Maven](https://github.com/diffplug/spotless/tree/main/plugin-maven)、[Prettier CLI](https://prettier.io/docs/cli)、[Ruff formatter](https://docs.astral.sh/ruff/formatter/)、[RuboCop 配置](https://docs.rubocop.org/rubocop/1.68/configuration.html)。
