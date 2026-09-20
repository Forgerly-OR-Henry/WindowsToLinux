# v1 多语言样例历史验证记录

本文件保留首次样例的验证时间与适用范围，仅对应升级前的 v1 行为；v2 业务升级的实际结果以 [VERIFICATION.md](VERIFICATION.md) 为准。

验证日期：2026-09-19。环境：Windows x64。本次统一运行器实际完成 **8/8 项目依赖安装、构建与业务验收**，5 个网页通过 Chromium 浏览器流程；3 个控制台工具通过真实文件及故障检查。Linux 实机和产品部署未验证。

## 实际结果

| 项目 | 构建结果 | 已实际验证的业务和故障 |
|---|---|---|
| 任务看板 | Maven + npm/Vite 通过 | 创建、编辑、完成、标签/优先级筛选、分页、统计、操作记录、非法输入、浏览器、SQLite 重启；停止 Java 后业务访问失败 |
| 文件收发站 | Go Modules + npm/TypeScript 通过 | 网页多文件上传、中文/空格/同名文件、大小/SHA-256、逐字节下载比对、删除、路径校验、上传中断清理、SQLite 重启、Go 不可用时网关 502 |
| 资产借还 | .NET locked restore/publish + npm 通过 | 登记、借出、归还、查询/统计/历史、同幂等键不重复记账、冲突 409、浏览器、SQLite 重启、C# 不可用时网关 502 |
| CSV 检查 | Composer lock + pip hashes 通过 | 缺失值/重复行/类型错误、BOM、引号内逗号和换行、非法 CSV/规则、问题行、历史、浏览器 JSON 下载、SQLite 重启、Python 不可用时不保存结果 |
| 问卷评分 | Gradle Wrapper/lock + Bundler + npm/Vite 通过 | 动态问卷、必填/范围/JSON 类型校验、真实 Ruby 评分 87、分项解释、幂等、浏览器历史、SQLite 重启、Ruby 不可用时不保存结果 |
| 日志统计 | Cargo locked + CMake/C++20 通过 | 7 行日志的级别/时间筛选、错误排行、时间分布字段、1 行无法解析、终端进程/JSON 导出、中文空格路径、缺文件、子程序缺失/协议错误/超时 |
| 目录差异 | Python 标准库 + CMake/C++20 通过 | C++ 扫描、Python SHA-256、SQLite 跨进程快照、增删改比对、路径过滤、JSON 导出、Windows 目录联接跳过、子程序缺失/协议错误/超时；失败不新增快照 |
| 二进制检查 | Cargo locked + CMake/C17 通过 | 小端格式、数量/汇总/CRC-32、多文件批量、空文件、截断和损坏拒绝、中文空格路径、JSON 导出、子程序缺失/协议错误/超时 |

三个控制台故障测试还读取替代子程序 PID，在主程序返回、测试进程组清理之前确认其已退出，避免由测试运行器事后清理掩盖工具自身的超时回收缺陷。所有服务/子进程均由本次运行器负责，结束后再次检查未发现本任务残留进程。

Windows 当前账户无法创建普通符号链接（WinError 1314），因此实际使用目录联接验证“不跟随链接”；C++ 同时跳过标准符号链接与 Windows reparse point。Linux 符号链接行为仍需实机核对。数据目录、输入文件和工作目录分别覆盖中文与空格；两次启动重新分配端口，持久化数据保持一致。

## 工具版本与入口

| 工具 | 实测版本 |
|---|---|
| Java / Maven | JDK 21.0.10 / Maven 3.9.12 |
| Node.js / npm | 24.16.0 / 11.13.0 |
| Go | 1.24.13 windows/amd64 |
| .NET SDK | 10.0.300 |
| PHP / Composer | 8.3.33 NTS / 2.8.12 |
| Python | 3.12.10 |
| Kotlin / Gradle Wrapper | 2.0.21 / 8.10.2 |
| Ruby / Bundler | 3.3.12 / 2.5.22 |
| Rust / Cargo | 1.95.0 GNU Windows 工具链 |
| C / C++ | MinGW GCC/G++ 16.1.0，C17 / C++20 |
| CMake | 4.2.3-msvc3，Ninja generator，实际编译器为 MinGW |
| Playwright | 1.62.1，Chromium，与仓库版本一致 |

最终入口在仓库根目录执行：

```powershell
python src/app/main/src/test/python/polyglot_runner.py verify-all --work .ai-workspace/tmp/polyglot-final --tools .ai-workspace/progress/polyglot-tools.json --host 127.0.0.2
```

`--tools` 是本机临时工具映射，不是样例运行依赖。缺失的 Go/PHP/Ruby 在任务目录内准备；.NET 包通过官方 NuGet 包缓存完成 locked restore；Go 模块仍由官方校验和数据库验证。Rust 使用本机已经存在、经版本核对的 1.95.0 GNU 编译器。SQLite JDBC 沿用仓库 3.46.0.0；所有新增依赖仅属于样例或测试设施。

本机 PHP 在 127.0.0.1 曾出现响应重置及浏览器加载超时；相同程序使用可配置的 127.0.0.2 后通过，最终统一测试使用该回环地址。未用重试掩盖业务失败，也未改动系统网络设置；不能由此推定所有机器上的 127.0.0.1 均有问题。

本地保留证据在 `.ai-workspace/progress/history/2026-09-19-multilanguage-evidence/`：八项目 results.json、实际工具版本/命令/退出码日志、五个浏览器截图、139 项 JUnit 摘要及迁移摘要清单。依赖缓存、测试副本、SQLite 与上传数据均为临时产物，不属于提交的样例源码。

## 迁移回归与范围

- 原有 25 个组合、125 个场景迁入 `test/single-language`，迁移瞬间 1,080 个文件的路径相对清单和 SHA-256 一致；之后仅修改说明文档的导航与新增迁移记录，场景源码及锁文件不变。
- 迁移相关 138 项既有 JUnit 测试，加新增混合矩阵门禁 1 项，共 **139 项通过，0 失败、0 跳过**。矩阵核对 8 个成功目录、5 个网页、6 个 SQLite 所有者及全部 12 种源码语言。
- 本任务早期调用根 Maven 相关 reactor 时遇到工作区中既有 `BackupUseCaseTest` 引用 `BackupManifest.LEGACY_SCHEMA_VERSION` 的编译错误；使用已存在的 JUnit classpath 重新编译并执行受影响测试，通过上述 139 项。本记录不代表整个根 Maven reactor 通过，也不判断并行任务之后对此错误的修复状态。
- 本次没有修改产品部署模型、协议、界面或控制台运行能力；Linux 构建、目标机部署、发布、回滚、恢复均为 **待验证**，不能从 Windows 本地样例成功推定通过。
