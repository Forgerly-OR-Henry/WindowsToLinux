# 2026-10-18 多语言 STATIC 部署回归与访问入口

当前工作区的八个多语言项目全部通过本轮真实服务器 STATIC 验收：8 项通过，失败、错误、跳过均为 0，部署模型调用均为 0。SSH 连接和产品环境准备另各通过 1 项。本轮没有发现导致这些用例崩溃的静态部署回归。

服务器为本轮用户提供的 Ubuntu 24.04 主机，SSH 指纹为 `SHA256:JeREPaEndteqRiK43Go2fDVrYfiVDANAZEBr/P5Hogw`。全部 SSH、环境准备、工具链、构建、发布和生命周期写入由产品执行。监督侧只启动验收程序，没有手工写入服务器，也没有启动 ASSISTED 或 AGENT。凭据不进入本记录或 Git。

访问地址和启动命令来自 `DeploymentApplicationsService.usage`，与产品管理界面使用同一份发布用法；导出前经产品 inspect 核对应用、发布和归属身份。下表为各项目结束时的真实状态及观测时间。

| 项目 | 访问入口 / 状态 | 最后观测时间（UTC+8） | JUnit 耗时 |
| --- | --- | --- | ---: |
| 任务看板 | [http://45.192.105.213:46100](http://45.192.105.213:46100)；RUNNING，自启关闭 | 2026-10-18 12:43:30 | 979.005 秒 |
| 文件收发站 | [http://45.192.105.213:46110](http://45.192.105.213:46110)；RUNNING，自启关闭 | 2026-10-18 12:54:05 | 616.384 秒 |
| 资产借还网站 | [http://45.192.105.213:46120](http://45.192.105.213:46120)；RUNNING，自启关闭 | 2026-10-18 13:04:37 | 626.657 秒 |
| CSV 数据检查网站 | [http://45.192.105.213:46130](http://45.192.105.213:46130)；RUNNING，自启关闭 | 2026-10-18 13:18:17 | 814.501 秒 |
| 问卷评分网站 | [http://45.192.105.213:46140](http://45.192.105.213:46140)；RUNNING，自启关闭 | 2026-10-18 13:40:17 | 1315.233 秒 |
| 日志统计工具 | 按需运行，INSTALLED；启动命令见下文 | 2026-10-18 13:45:45 | 322.438 秒 |
| 目录差异工具 | 按需运行，INSTALLED；启动命令见下文 | 2026-10-18 13:48:40 | 169.899 秒 |
| 二进制数据检查工具 | 按需运行，INSTALLED；启动命令见下文 | 2026-10-18 13:50:38 | 113.412 秒 |

五个网站已由产品在原验收收尾后重新启动，供用户自行验收；自启保持关闭。三个 CLI 为按需应用，正常状态是 INSTALLED，执行结束后不会保留常驻进程。

以下命令在服务器终端执行。管理界面的固定启动命令到 `--` 为止，后面的参数为本轮样本的人工复验示例。

**日志统计工具**

```bash
sudo /usr/local/lib/windowstolinux/managed-helper app-run wtl-static-log-analyzer-o2qbh6ng0 15aa0a2e670c62bf031556bdd2758a44e2496c7a7d5add87a8ebee36ae173a9d -- --input samples/events.log --format json
```

**目录差异工具**

```bash
sudo /usr/local/lib/windowstolinux/managed-helper app-run wtl-static-directory-diff-o6wt94tlg 9f405992175620628b1c8d772b10f2555efddfd864a3fbe987bd17d1181d903b -- --format json snapshot --root samples/baseline --name manual-acceptance-20261018
```

**二进制数据检查工具**

```bash
sudo /usr/local/lib/windowstolinux/managed-helper app-run wtl-static-binary-inspector-o958m1e48 c27735c1a5687ba4d6dbe9234797d81b588887e21756c25e749f4ef811aad3ac -- --input samples/normal.bin --format json
```

日志样本的产品健康探针核对 `"matched": 6`，二进制样本核对 `"records": 5`；目录快照核对 `"status": "complete"`。目录示例使用新名称，避开验收时已创建的 `live-baseline`；重复手动创建同名快照会按业务规则拒绝，可自行换名。

验证范围：五站均执行真实外部 HTTP、跨语言业务、应用重启和持久化检查。任务看板创建记录并执行 STOP/START/RESTART；文件收发上传并两次比对 64KiB 文件；资产借还完成借用、审批、出借、归还；CSV 核对 3 行、6 项问题、1 行有效及 worker 重启；问卷核对 84.21 分及重启后记录。三个 CLI 的原产品 COMMAND 健康探针真实运行程序并匹配上述输出，随后再次检查安装状态并导出启动命令。

本轮是定点真机验收，不是全仓测试；没有执行浏览器交互、standard 规模或完整故障矩阵，也没有验收容器运行后端。用户后续浏览器自行验收结果尚未计入。

本次只新增 `DeploymentStaticRegressionLiveAcceptanceTest`：复用原 STATIC 完整验收，增加 CLI 精确方法入口、网站产品 START 交付及管理用法导出；没有修改原真机测试类、生产部署流程、ASSISTED 或 AGENT。必要编译与新增 Java 文件的 IDEA 格式检查通过。

可复现入口：根 Maven 的 `-pl src/mod/deployment -am test`，显式选择 `-Dtest=DeploymentStaticRegressionLiveAcceptanceTest#deploysWebsitesForManualAcceptance` 或 `-Dtest=DeploymentStaticRegressionLiveAcceptanceTest#deploysCliForManualAcceptance`，配合 `-Dsurefire.failIfNoSpecifiedTests=false`、`-Dmanaged.runtime.staticLive=true`、单项目 `managed.live.projects`、本轮 SSH 参数和指纹，以及已存在且位于 `.ai-workspace/evidence` 下的新 `managed.live.deliveryDirectory`。SSH 密码只通过进程环境 `WINDOWSTOLINUX_TEST_SSH_PASSWORD` 传入。不要重跑已成功项目来覆盖同名交付文件。

原始证据保留在忽略的 `.ai-workspace/evidence/static-regression-20261018/`：每项目 `*-01.log`、JUnit XML、产品用法 JSON 和 `summary.json`；产品受保护运行数据保留在各 JSON 的 stateDirectory。首次原图 OCR 读数与放大读数有一字符差异，前者认证失败，后者通过；失败证据保留，未修改密码或生产认证实现。

静态阶段已完成。按用户安排，下一阶段等待用户重装后再测试 AI 辅助；本轮不继续 Agent 测试。
