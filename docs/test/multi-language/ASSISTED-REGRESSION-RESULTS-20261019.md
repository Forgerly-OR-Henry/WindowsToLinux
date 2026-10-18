# ASSISTED 多语言真机回归结果与 Token 用量

统计生成时间：2026-10-19T05:48:50.207867+08:00（Asia/Singapore）。

8 个项目均经产品完成 ASSISTED 部署、业务及生命周期验证，并导出与管理界面同源的访问或启动信息。网站交付时 RUNNING、自启动 DISABLED；CLI 为按需运行的 INSTALLED。STATIC 已由用户在服务器重装前验收，本记录针对重装后的 ASSISTED；Agent 部署仍未恢复。

任务看板、日志分析与问卷评分经过原任务对账恢复后完成交付，原失败或 UNKNOWN 历史保留。问卷评分首轮在维护结束审批收到普通 JSON 文本而中止；严格原生结果要求保留，随后由产品结清原任务并对原应用完成业务验收。此处的8项目交付结果不表示每个最初部署尝试均无中断。

## 网站

| 项目 | 语言 | 产品导出地址 | 导出时刻（Asia/Singapore） |
| --- | --- | --- | --- |
| 任务看板 | TypeScript + Java | [http://45.192.105.213:46100](http://45.192.105.213:46100) | 2026-10-19T01:56:13+08:00 |
| 文件传输 | TypeScript + Go | [http://45.192.105.213:46110](http://45.192.105.213:46110) | 2026-10-19T04:08:09+08:00 |
| 资产借还 | JavaScript + C# | [http://45.192.105.213:46120](http://45.192.105.213:46120) | 2026-10-19T04:29:07+08:00 |
| CSV 检查 | JavaScript + PHP + Python | [http://45.192.105.213:46130](http://45.192.105.213:46130) | 2026-10-19T04:53:32+08:00 |
| 问卷评分 | TypeScript + Kotlin + Ruby | [http://45.192.105.213:46140](http://45.192.105.213:46140) | 2026-10-19T05:46:40+08:00 |

交付前另对五个产品导出入口仅执行 HTTP GET，均返回200及HTML主页；证据为本地delivery-readonly-http-check.json。这次只读检查不替代已执行的业务及生命周期测试。

## CLI 启动命令

以下是产品管理接口的原始命令，需在该服务器执行；应用 ID 与身份摘要不作缩写。

### 日志分析（Rust + C++）

```bash
sudo /usr/local/lib/windowstolinux/managed-helper app-run wtl-assisted-log-analyzer-xiiwt1zl8 f59b78fc830e7134292caa12d1317ca664719320d9357daa688cfe0147f5a4c6 --
```

### 目录差异（Python + C++）

```bash
sudo /usr/local/lib/windowstolinux/managed-helper app-run wtl-assisted-directory-diff-3awqbc9es 62ca1fd292d3e765b26ff991570f1da6366ca457b01557de31f6c7404c65eabe --
```

### 二进制检查（Rust + C）

```bash
sudo /usr/local/lib/windowstolinux/managed-helper app-run wtl-assisted-binary-inspector-3tq3s0huo 8d42bb34e838aed3d4d06b54a23834c32743ba1c8702047ce028266b61e1f306 --
```

## 实际提供方 Token

主测试配置使用 deepseek-flash。按物理 generation ID + attempt 去重，包含失败部署、原任务恢复、API 定点诊断及管理调用；不是 Codex 自身用量，也不是单次成功部署成本。能力验证接口没有提供 usage，另列未知。

| 用途 | 物理尝试 | 已知输入 | 已知输出 | 已知合计 | 输入或输出未知的尝试 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 部署/分析 | 677 | 3,001,122 | 170,959 | 3,172,081 | 1 |
| 独立审批 | 1,153 | 7,930,779 | 719,446 | 8,650,225 | 2 |
| 总计 | 1,830 | 10,931,901 | 890,405 | 11,822,306 | 3 |

输入中已知缓存命中 5,399,240，未命中 5,532,661；输出中已知推理 648,709。缓存与推理是输入/输出的组成部分，不能再次相加。推理字段缺失 804 次，不视为 0。

另有 284 项能力验证任务用量及物理请求数未知；1 条保留日志未提供可用物理 trace，不能虚构请求或补零。其中唯一无 trace 记录来自 Task Board19 发送前预算拦截；原 UNKNOWN 元数据保留，已验证未发送，故不增加物理请求数或虚构 Token。持久审计文件 usage-audit-25.log覆盖71个目录、1806条保留用量日志。宿主标准日志每类别最多保留100条；本次有25个stdout物理尝试在当前保留日志中未匹配，均按原stdout的提供方字段纳入总账，未当作0或漏计。

| 项目（含关联失败、恢复和诊断） | 已知 Token |
| --- | ---: |
| 任务看板 | 2,785,564 |
| 文件传输 | 3,205,216 |
| 资产借还 | 577,502 |
| CSV 检查 | 592,797 |
| 问卷评分 | 1,060,697 |
| 日志分析 | 2,934,837 |
| 目录差异 | 234,810 |
| 二进制检查 | 430,883 |

## 验证及修改记录

业务检查覆盖任务持久化、文件 64 KiB 上传下载及重启后比对、资产借还闭环、CSV 后台任务/导出及工作进程重启、问卷跨语言计分及持久化，以及三项 CLI 的实际输出。网站还经过产品启停、重启及自启动开关检查。64 KiB 文件检查不能替代 Agent 的 64 MiB 标准场景；本轮没有运行该场景、Agent 部署或全项目测试，浏览器交互留给用户验收。

所有服务器写入均由产品执行；监督侧仅启动程序、修改程序和只读诊断。UNKNOWN 原任务均先产品对账再继续，未手工部署或重放副作用。两项需要 Linux /bin/bash 的局部行为测试在本机 Windows 环境未运行；真实远端构建通过不能替代那两项独立用例。

测试配置经历 LOW/NONE 审批8192、LOW/LOW 审批8192、LOW/LOW 审批32768/120秒，以及后续32768/600秒阶段。不得把不同阶段的成功结果当作同一配置的统一成功证据。提高输出和期限上限会提高潜在单次调用成本；实际总账包含这些尝试。

源码修复、影响范围、定点检查及后续 Agent 调试点见 [修复记录](ASSISTED-REGRESSION-FIXES-20261018.md) 和 [精确补丁](ASSISTED-REGRESSION-20261018-fixes.patch)。本地 evidence 保留每个项目的管理导出 JSON、JUnit XML、原始日志、provider Token 汇总及只读审计结果；凭据不进入报告或 Git。

每个管理导出均匹配正常结束、0失败/错误/跳过的JUnit记录：task-board-delivery-01、file-transfer-11、asset-lending-01、csv-inspector-01、survey-scoring-delivery-01、log-analyzer-delivery-03、directory-diff-delivery-01、binary-inspector-02。

交付收尾已确认本轮本地验收/诊断Java进程全部退出；删除53个原始JFR和两个临时编译目录，保留过滤异常、原日志及清理哈希清单。57个复现辅助文件归档到本地evidence的reproduction-tools目录，未提交运行数据或清理产品状态。服务器上的五个交付网站继续运行供用户验收，CLI保留按需启动状态。
