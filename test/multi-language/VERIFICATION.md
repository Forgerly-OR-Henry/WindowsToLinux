# v2 多语言业务验收记录

验证日期：2026-09-19 至 2026-09-20；环境：Windows x64。最终连续执行 `verify-all --profile standard`，**8/8 项目依赖安装、构建和标准业务验收通过**，五个网页均完成真实 Chromium 操作。首次 v1 记录原样保留主体于 [历史记录](VERIFICATION-v1.md)，不用于证明本版通过。

## 功能、规模与故障结果

| 项目 | 实际主数据规模 | 已通过的主要检查 | 验收耗时 |
|---|---|---|---:|
| [项目任务看板](success-task-board/README.md) | 10,008 个任务 | 项目/成员隔离、依赖和状态流、10 路版本竞争、评论、事务中断、浏览器 | 120.312 s |
| [文件收发站](success-file-transfer/README.md) | 64 MiB 文件、历史版本 | 分块冲突/去重、重启续传、10 路完成只发布一次、下载摘要、发布中断、浏览器 | 20.594 s |
| [资产借还](success-asset-lending/README.md) | 10,005 件资产 | 多资产单、审批占用原子性、10 路竞争、部分归还/维修、重复请求、浏览器 | 84.266 s |
| [CSV 工作台](success-csv-inspector/README.md) | 100,000 行、22,937 个问题 | 逐条独立核对、后台取消/重试、worker 中断、报告发布边界、协议故障、浏览器 | 95.125 s |
| [问卷管理](success-survey-scoring/README.md) | 10,013 份提交 | 真实 Ruby 评分、发布版本不可变、条件题、规则变化、10 路幂等、历史、浏览器 | 127.328 s |
| [多源日志](success-log-analyzer/README.md) | 100,000 条，文本/JSONL/gzip | 顺序/格式一致、筛选汇总、坏文件继续、资源上限、严格协议及子程序回收 | 2.750 s |
| [目录快照](success-directory-diff/README.md) | 10,001 个文件 | 独立 SHA-256、同大小变化、重命名候选、联接跳过、扫描/提交中断、10 路同名竞争 | 111.765 s |
| [分块二进制](success-binary-inspector/README.md) | 100,000 条、98 块、两类记录 | 独立 CRC/汇总、过滤、头/类型/长度/数量/UTF-8/CRC 错误偏移、批量继续、子程序故障 | 2.219 s |

以上耗时仅为该机器上的业务验收阶段，不含安装和构建，不是吞吐保证。共 59 个验收检查组，每组可能含多个业务断言；不与 JUnit 测试数量混计。种子 20260919、实际规模和每组结果记录在 report.json。看板/资产/问卷规模记录均经真实业务接口写入，问卷逐次调用 Ruby；CSV、日志、目录与二进制预期由独立生成器/参考计算及业务不变量确定。

五个网页已验证列表、详情、编辑/处理、历史、统计或评分、加载和错误反馈；CSV 额外在浏览器中完成 20000 行任务的取消/重试。跨项目、文件夹、问卷版本和规则的数据边界有具体数据断言。Node 网关及 Kotlin/PHP 的真实下游响应影响结果，移除或替换关键协作模块会使对应操作失败。

故障覆盖：写入/跨语言调用/报告发布边界中断、下游不可用、超时、错误协议、缺字段/结束标记、异常退出、数据损坏以及失败后再次正常操作。文件收发和 SQLite 项目验证重启状态；CSV 的中断任务必须显式重试。三个 CLI 的故障检查在运行器最终清理前核验子程序 PID 已退出。

资源指标通过 Windows Job Object 读取可获得的 peakProcessBytes/peakJobBytes，属于操作系统报告的进程/作业内存峰值，不是稳定 RSS、跨机器基准或性能承诺；进程已结束或无句柄时只记录可获得字段。

## 复现与工具

```powershell
python src/app/main/src/test/python/polyglot_runner.py verify-all --profile standard --work .ai-workspace/tmp/mixed-check --tools <本机工具映射.json> --host 127.0.0.2
```

quick 使用较小主数据但保留业务/故障检查；标准规模见 [入口说明](README.md)。每个项目 README 也提供独立构建和人工体验步骤，不依赖运行器。运行时 SQLite、上传内容、大样本及报告不提交。

实测工具：JDK 21.0.10 / Maven 3.9.12；Node 24.16.0 / npm 11.13.0；Go 1.24.13；.NET SDK 10.0.300；PHP 8.3.33 / Composer 2.8.12；Python 3.12.10；Kotlin 2.0.21 / Gradle 8.10.2；Ruby 3.3.12 / Bundler 2.5.22；Rust/Cargo 1.95.0 GNU；MinGW GCC/G++ 16.1.0；CMake 4.2.3（Ninja）；Playwright 1.62.1 / Chromium。每项版本命令和构建退出码均保留。

构建使用隔离目录和任务缓存。Java/Kotlin SQLite JDBC 沿用 3.46.0.0；Go modernc SQLite、C# Microsoft.Data.Sqlite、PHP PDO SQLite 和 Python sqlite3 保持各自所有权。日志新增 flate2 1.1.10 用于真实 gzip 解压，并生成 Cargo.lock。所有新增依赖仅属于样例或测试设施。

实施中实际遇到并修复：PHP 格式化器默认采用 8.4 写法，与本项目 8.3 不符；Windows C++ 文件时间精度和 Python stat/fstat 创建时间语义不同；Gradle 启动脚本 PID 与实际 JVM PID 不同。最终验收运行包含这些修正。首次 Kotlin 下载超时、Windows 沙箱 TLS 凭据失败通过任务内官方缓存/工具准备解决，未改系统网络或全局语言配置。

## 单语言保留与相关回归

- 原有 125 个场景的 **1077 个目录内文件**（源码、配置、锁文件、样本）逐路径 SHA-256 与任务开始一致，未丢失文件。起始完整清单含 1080 个文件，另 3 个为目录根索引/记录。
- 工作期间其他任务新增 Python 标准库组合的 40 个文件，并更新单语言 matrix.json / README.md；这些修改保留，当前为 26 个组合、130 个场景。不能把目录整体摘要说成完全未变。
- 首次定向 Maven 实跑 144 项：143 项既有检查通过，唯一失败为混合矩阵中写死 25 组合的过时断言。该测试已改为核验单语言矩阵路径有效，具体场景仍由其自己的矩阵测试逐项检查；修正后的混合矩阵门禁独立运行通过 1 项。
- 后续根 reactor 重跑被并行产品工作中 `ProjectComponentDiscovery` 引用缺失 `ApplicationBundleInspector` 阻断。其未完成的编译也使当前分析器 target 不完整，因此没有将后续全组重跑标为通过；未修改这些产品文件。本交付不声称当前整个 Maven reactor 已通过。

## 证据与未验证范围

保留证据：`.ai-workspace/progress/history/2026-09-20-multilanguage-v2-evidence/`，含八项目结果、命令/版本日志、五个截图、原生故障命令、单语言前后摘要、分次 JUnit 记录和根编译阻断记录。历史命令保留当时临时绝对路径，不作为永久机器配置。

本任务启动的验证进程已退出，临时工具/缓存、构建副本、运行数据和准备脚本已清理，保留报告与截图。受测副本的 198 个源码文件内容核对一致，其中 Ruby 故障测试恢复时仅产生换行差异，已记录并将后续清理改为原字节恢复。

Linux 实机、WindowsToLinux 产品部署、发布、回滚及恢复均为待验证。本机普通符号链接创建返回 WinError 1314，实际验证了 Windows 目录联接及重解析点跳过；普通符号链接的实际平台检查仍待具备权限的环境。目录一致性为两遍扫描/摘要校验，不宣称操作系统原子卷快照。
