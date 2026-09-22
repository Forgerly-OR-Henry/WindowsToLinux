# 多语言业务验收项目

八个独立项目覆盖 12 种源码语言；每种组合仅一个成功样例。所有数据库均为嵌入式 SQLite，不安装数据库服务。Node.js 是 JavaScript/TypeScript 的运行时，不另外计作源码语言。

| 项目                                                     | 协作链                     | SQLite 写入者 |
| -------------------------------------------------------- | -------------------------- | ------------- |
| [任务看板](success-task-board/README.md)                 | TYPESCRIPT + JAVA          | backend       |
| [文件收发站](success-file-transfer/README.md)            | TYPESCRIPT + GO            | backend       |
| [资产借还网站](success-asset-lending/README.md)          | JAVASCRIPT + CSHARP        | backend       |
| [CSV 数据检查网站](success-csv-inspector/README.md)      | JAVASCRIPT + PHP + PYTHON  | web           |
| [问卷评分网站](success-survey-scoring/README.md)         | TYPESCRIPT + KOTLIN + RUBY | backend       |
| [日志统计工具](success-log-analyzer/README.md)           | RUST + CPP                 | 无            |
| [目录差异工具](success-directory-diff/README.md)         | PYTHON + CPP               | cli           |
| [二进制数据检查工具](success-binary-inspector/README.md) | RUST + C                   | 无            |

当前为 v2 中型业务样例，每个目录包含业务说明 `BUSINESS.md`、完整源码、真实依赖声明与锁文件、固定样本、规模数据生成器及独立操作步骤。网页按列表、详情、编辑和历史拆分交互；业务状态、版本冲突、事务和持久化由服务端处理。身份为固定演示选择，不属于认证。控制台通过实际 C/C++ 子程序处理文件；移除关键协作模块会使对应验收失败。

## 仓库验收入口

样例独立于根 Maven reactor。Python 运行器位于 `src/app/main/src/test/python/polyglot_runner.py`；浏览器测试位于相邻 `browser/`，Playwright 1.62.1 与产品前端版本一致，但依赖独立管理。

在仓库根目录准备浏览器测试依赖：

```powershell
npm --prefix src/app/main/src/test/browser ci
node src/app/main/src/test/browser/node_modules/playwright/cli.js install chromium
```

工具链在 PATH 中时直接使用以下命令。首次依赖安装需要访问各语言官方包仓库；缺工具或安装失败会报错，不能当作跳过后通过。

```powershell
python src/app/main/src/test/python/polyglot_runner.py build --project task-board --work .ai-workspace/tmp/mixed-check
python src/app/main/src/test/python/polyglot_runner.py run --project task-board --work .ai-workspace/tmp/mixed-check
python src/app/main/src/test/python/polyglot_runner.py verify --project task-board --profile quick --work .ai-workspace/tmp/mixed-check
python src/app/main/src/test/python/polyglot_runner.py verify-all --profile standard --work .ai-workspace/tmp/mixed-check
python src/app/main/src/test/python/polyglot_runner.py run --project binary-inspector --work .ai-workspace/tmp/mixed-check --cli-args --input samples/normal.bin --format json
```

`run` 网页时打印入口并持续运行，Ctrl+C 关闭本次进程；控制台转交 `--cli-args` 并保留退出码。`verify` / `verify-all` 均先构建，再执行 API、浏览器或真实文件断言；每次生成新的临时数据库验证重启，不污染样例源目录。

运行器支持 `--host`（默认 127.0.0.1）以及 `--tools tools.json`。工具映射值可为可执行文件路径，或由路径和固定参数组成的数组，例如：

```json
{
  "java": "C:/Tools/jdk-21/bin/java.exe",
  "mvn": ["C:/Tools/maven/bin/mvn.cmd", "-Dmaven.repo.local=C:/Temp/mixed-m2"],
  "composer": ["C:/Tools/php/php.exe", "C:/Tools/composer.phar"],
  "python": "C:/Tools/Python312/python.exe",
  "env": {"JAVA_HOME": "C:/Tools/jdk-21", "PLAYWRIGHT_BROWSERS_PATH": "C:/Temp/playwright-browsers"}
}
```

其余工具键为 npm、node、go、dotnet、ruby、bundle、cargo、cmake、gcc（含故障注入小程序）、g++、ninja；支持独立缓存环境变量。可选 gradle 指向与 Wrapper 一致的 8.10.2 发行版。CMake 的 CC/CXX 或相应工具须在 PATH 中。测试输出包含各项目 commands.json、版本输出与命令日志、五个网页截图，以及 verification/<运行标识>/report.json 和总 results.json。报告分别列出业务、规模、故障、恢复及浏览器检查、种子、实际规模、耗时和可采集的内存指标；这些耗时不代表吞吐保证。所有测试启动的服务及子程序在结束或异常时回收；不操作用户已有进程。

### 快速与标准配置

两种配置执行相同的业务和故障检查，主要区别为规模；浏览器使用自己的少量业务数据（CSV 取消重试使用 20000 行）。标准配置是交付门槛，默认即 standard。

| 项目       | quick 的主数据规模 | standard 的主数据规模 |
| ---------- | -----------------: | --------------------: |
| 任务看板   |     创建 60 个任务 |     创建 10000 个任务 |
| 文件收发   |              2 MiB |                64 MiB |
| 资产借还   |     登记 50 件资产 |     登记 10000 件资产 |
| CSV 检查   |            5000 行 |             100000 行 |
| 问卷评分   |      60 次真实评分 |      10000 次真实评分 |
| 日志分析   |            5000 条 |             100000 条 |
| 目录快照   |         501 个文件 |          10001 个文件 |
| 二进制检查 |            5000 条 |             100000 条 |

种子固定为 20260919。带状态的并发检查使用至少 10 个竞争请求；CLI 批量汇总由固定数据和独立参考计算核对。故障检查包含中断、下游不可用、超时、协议/字段/结束标记错误、异常退出和关键模块移除，失败后再次执行正常操作。子程序退出检查在运行器最终清理之前完成。

## 数据与失败语义

Java、Go、C#、PHP、Kotlin、Python 分别独占各自项目数据库，其他组件通过接口通信。建表可重入、不清空当前版本数据，写操作使用参数化 SQL 与事务。新版直接使用新数据目录，不提供旧数据库迁移。任务编辑以记录版本检测冲突；借还、问卷提交、CSV 创建/重试使用幂等标识；上传会话按分块编号去重，并发完成只发布一个版本；同名目录快照只能创建一次。

Web 数据采用 HTTP 与 UTF-8 JSON，文件收发和 CSV 上传使用文件流。CSV 由独立 PHP worker 消费 SQLite 队列，按批保存 Python NDJSON 问题；中断后需显式重试，未完成任务不发布成功报告。C/C++ stdout 使用版本 2 的 NDJSON 消息和结束计数，stderr 为诊断；主程序采用参数数组启动，不拼接 Shell。正常、输入、文件和子程序错误分别有明确退出码，批量失败保留逐项状态。故障素材或替代子程序只在隔离副本产生，不增加失败样例目录。

`matrix.json` 记录组件、语言、构建方式、运行入口、依赖声明、SQLite 归属及验收入口。新增矩阵门禁检查 8 个独立目录、全部 12 种语言及真实文件，不把样例目录存在等同于产品可以部署。

## 验证范围

实际结果见 [VERIFICATION.md](VERIFICATION.md)。Windows 标准业务验收与 2026-09-20 Ubuntu 产品部署验收分别记录；后者覆盖八项目发布、代表性业务和按需入口，恢复、跨机迁移及其他平台仍待验证。原有 125 个单语言场景及历史验证记录见 [single-language](../single-language/README.md)。
