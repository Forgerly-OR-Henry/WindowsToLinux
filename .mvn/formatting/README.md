# 主项目 IDEA 排版与代码检查

格式化使用本机 IntelliJ IDEA 的引擎和 [idea.xml](idea.xml)，基础缩进、编码和换行由根目录 [.editorconfig](../../.editorconfig) 覆盖。已验证 Windows x64 的 IDEA 2026.2.2；升级 IDEA 可能改变排版结果，团队应使用同一版本。

## 检查范围

- 检查 `src/` 下 IDEA 支持的文件，包括生产 Java 和各模块 `src/test/` 中的 Java 测试、浏览器测试及配置；同时检查根 POM、Mod 作者父 POM 和共享格式方案。
- 根目录 `test/` 是独立的多语言样例工程，整体排除。不要将此规则写成排除所有名为 `test` 的目录。
- 暂不检查 Python、Shell、PowerShell、C/C++、C#、Go、Rust、PHP、Ruby。这些文件不会被本入口改写。
- 归档 `history-src/`、文档目录、开发探针、生成产物、锁文件、二进制和原格式保留证据不参与检查。精确范围可用 `--list` 查看。

当前只登记主项目实际使用的格式类型：Java（`.java`）、JavaScript（`.mjs`）、XML/SVG、JSON、TOML、Properties 和 SQL。使用安装包自带的插件；某个文件被 IDEA 跳过会让检查失败，不能当作通过。新增需要检查的文件类型时，再更新 `format.py` 的类型清单。

## 执行

Maven 默认不执行 IDEA 格式检查，普通 `mvn clean verify` 不依赖本机 IDEA 或格式启动器所需的 Python。手动运行格式工具或启用 `idea-check` Profile 时，需要 Git、Python 3 和带运行时的 IntelliJ IDEA。把 `idea64.exe` 放入 `PATH`，或者仅在当前终端设置 `IDEA_HOME` 指向 IDEA 安装目录，无须安装额外格式工具。

在仓库根目录执行：

```powershell
python .mvn/formatting/format.py --list
python .mvn/formatting/format.py --check
python .mvn/formatting/format.py --check --only java
python .mvn/formatting/format.py --check --path src/moddev/<project>
python .mvn/formatting/format.py --write
mvn clean verify -Pidea-check
```

`--path` 将 Git 文件枚举和后续格式检查限定到已有的仓库内文件或目录，可重复传入多个目标。
路径会规范化并拒绝仓库外、缺失或通过链接重定向的目标；原格式范围和生成物排除仍适用。
未传入时保留默认完整工程范围。独立 ModDev 项目可使用该入口，不启动 APP 或检查其他开发项目。

两种模式均在保留目录层级及 EditorConfig 的临时副本上执行 IDEA 格式化，然后逐字节比较结果；这是为了避免 IDEA dry-run 与实际写入结果不一致。`--check` 验证源码内容不变；只有 `--write` 会更新源码，检测到源文件被并发修改时停止写入。格式不符、跳过文件、缺少工具或启动失败均返回非零。两者都要求 UTF-8、LF 和末尾换行。工具或规则升级可能改变排版结果，格式差异需审阅后再执行写入。

`idea-check` 没有自动激活条件，需使用 `-Pidea-check` 手动启用。启用后，根 POM 在 `verify` 阶段调用一次完整主项目检查，不向子模块继承；`install`、`deploy` 也会经过该阶段，`compile`、`test`、`package` 不会。仅构建子模块（例如 `mvn -pl ... verify -Pidea-check` 或在子模块执行 Maven）不会自动执行根级检查，此时另运行上述 Python 命令。Maven 使用 JDK 21；格式引擎使用 IDEA 自带运行时。可用 `-Dformat.python=Python完整路径` 指定 Maven 调用的 Python。

## 在 IDEA 中使用

重新加载 Maven 项目后，可在 Maven 工具窗口的 Profiles 中勾选 `idea-check`，再执行根项目的 `verify`；取消勾选即恢复默认关闭。也可在 Maven 运行配置的 Profiles 字段填写 `idea-check` 并保存。IDEA 不会仅因打开项目就自动启用此 Profile；普通 IDE 构建、编辑器检查及保存动作与该 Maven 开关独立。

首次配置或更新 `idea.xml` 后，在仓库根目录执行：

```powershell
python .mvn/formatting/configure_idea.py
```

该命令根据本机 IDEA 安装包生成项目级 `.idea/codeStyles/`、`inspectionProfiles/`、`scopes/`，并只更新 `workspace.xml` 中的格式化和导入优化保存动作。`.idea/` 继续按仓库策略忽略；可重复执行的配置来源保存在本目录，不写全局 IDE 设置。重跑会重新生成此项目的 WindowsToLinux 检查方案与排版方案；自定义规则应同步维护配置入口。IDEA 已打开时，配置后重新打开项目，避免内存中的旧设置覆盖配置。

- 排版使用 `idea.xml` 和根 EditorConfig，保存时格式化当前受支持文件的全部内容。
- 保存 Java/JavaScript 时执行 Optimize Imports。Java 检查未使用导入、导入排序和通配符导入；导入按 java/javax、其他普通导入、静态导入分组，并使用单类导入，清空默认的强制星号导入包名单。
- 检查方案 `WindowsToLinux Main` 采用安装包为相关语言默认开启的检查及原严重程度，额外开启格式检查、通配符导入检查，并把导入排序提升为警告。包含空指针、类型与引用问题、未使用代码、常见资源/并发/API 问题及受支持配置文件检查；具体适用性取决于语言、SDK 和项目依赖。
- 所有生成的检查项仅在同名主项目范围内启用；保存动作通过排版排除范围使用相同边界。根目录 `test/`、归档、生成文件和 Python/Shell 不在范围中，各主模块的 `src/test/` 保留。范围按 Maven 模块内容根目录匹配，模块名沿用 `windowstolinux-*`。
- 新增插件或升级 IDEA 后，重新运行配置入口以更新检查清单。未登记的新检查默认关闭，防止绕过主项目范围。

确认 Maven 项目已导入、Project SDK 为 JDK 21 并完成索引后，编辑器会使用项目检查方案。需要全量扫描时，使用 Code → Inspect Code（也可通过 Find Action 搜索），选择范围与方案 `WindowsToLinux Main`。保存动作不会自动执行全项目扫描，也不执行 Code Cleanup 或业务逻辑快速修复。缺失且有歧义的 import 仍需使用 IDEA 提示选择正确类型。

显式启用的 `mvn verify -Pidea-check` 与 `format.py --check` 负责语言排版，不运行 IDEA Inspections，也不执行 Optimize Imports；IDE 检查通过、格式检查通过、编译通过和测试通过是不同结果。

## 缓存与清理

格式实例的配置、索引、日志、临时文件集中在根目录 `target/formatting/idea/`，可在检查结束后删除，后续自动重建。格式化副本和启动参数文件每次用完自动删除。完整引擎输出位于 `last-run.log`，格式不符的原文件路径由检查入口输出。

启动器根据 IDEA 安装包的 `product-info.json` 使用其运行时和启动参数，一次传入全部选定文件。独立配置及缓存允许检查与已打开的 IDEA 共存，不修改个人 IDE 配置；同一仓库不要同时启动多个格式检查。检查本身不下载 IDEA 或插件。

依据：[JetBrains 命令行格式化](https://www.jetbrains.com/help/idea/command-line-formatter.html)、[IDE 目录配置](https://www.jetbrains.com/help/idea/tuning-the-ide.html)。
