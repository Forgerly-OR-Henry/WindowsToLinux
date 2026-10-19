# ASSISTED 多语言首轮稳定性复测结果

记录时间：2026-10-19T13:05:28+08:00（Asia/Singapore）。

八个项目各发起一次完整部署，首轮通过 6/8，失败 2/8。首轮结果保留；本轮未修改生产源码、验收源码、样例或配置，没有对失败项目修复重试，也未用原任务恢复结果替代首轮结果。

固定配置沿用上次最终启动脚本：deepseek-flash，部署LOW、独立审批LOW，模型输出32768，审批期限600秒，单次运行已知Token阈值1000000。产品原有的有界模型纠正属于同一部署流程，不等同于外部重跑。

主机由产品重新连接核验，固定指纹 `SHA256:Ack2T3nH/7IiaMCkjxVVnAHLeHE4BTxbfA+bnLP+d8w`。环境准备首轮通过，所有远端安装、构建、发布、业务写入与管理操作均由产品完成；监督侧仅启动、观察和只读检查。

| 项目 | 首轮结果 | 结果或产品导出入口 | 已知Token |
| --- | --- | --- | ---: |
| 任务看板 | 首轮失败 | DEPLOYMENT_AI_OUTPUT_BUDGET_EXHAUSTED | 371,521 |
| 文件传输 | 首轮通过 | [http://45.192.105.213:46110](http://45.192.105.213:46110) | 614,045 |
| 资产借还 | 首轮通过 | [http://45.192.105.213:46120](http://45.192.105.213:46120) | 612,610 |
| CSV检查 | 首轮通过 | [http://45.192.105.213:46130](http://45.192.105.213:46130) | 597,602 |
| 问卷评分 | 首轮失败 | DEPLOYMENT_AI_OUTPUT_BUDGET_EXHAUSTED | 377,342 |
| 日志分析 | 首轮通过 | CLI，按需INSTALLED | 279,669 |
| 目录差异 | 首轮通过 | CLI，按需INSTALLED | 315,830 |
| 二进制检查 | 首轮通过 | CLI，按需INSTALLED | 285,735 |

任务看板在后端 `deployment.build.run.v1` 独立审批中失败，事件 `DEPLOYMENT_AI_OUTPUT_BUDGET_EXHAUSTED`。该次提供方输出32768 Token，全部为reasoning，finish=length，没有提交原生结论；程序未执行该构建，并完成候选清理。它是已确认的首轮失败，未改成成功，也未手工补部署。

问卷评分在 `deployment.toolchain.prepare.v1` 独立审批中触发相同输出耗尽：32768输出全部为reasoning，finish=length，未发布应用。本轮保留FAILED结果，未恢复或重试。

## 产品同源CLI启动命令

### 日志分析

```bash
sudo /usr/local/lib/windowstolinux/managed-helper app-run wtl-assisted-log-analyzer-jl1i05a24 9a3cc9ced0f55ecc7ed047fcd1fcb85002c44817ea988e1e1d815854b20ac402 --
```

### 目录差异

```bash
sudo /usr/local/lib/windowstolinux/managed-helper app-run wtl-assisted-directory-diff-jur12nq5c 6dd78a9891d997efde24053cd694460cf244b6f9971c19cb528fb456455df406 --
```

### 二进制检查

```bash
sudo /usr/local/lib/windowstolinux/managed-helper app-run wtl-assisted-binary-inspector-k286dvlww 3d91dce030af0dd432dd1d79468983e46d0168e4ccf2e9c1de348e0f6b305bba --
```

## 提供方真实用量

按物理generation ID及attempt去重：已知输入2,864,145，输出590,209，合计3,454,354 Token，共396次物理尝试。包含失败项目及管理调用；这是产品模型用量，不是Codex自身用量。

输入或输出未知的物理尝试0次；另有32项能力验证任务的用量及物理请求数未知，未补零。缓存命中和reasoning均为输入/输出子集，不重复相加。

## 验证边界与证据

业务及生命周期仅以各项目实际通过记录为准。文件传输采用64KiB样本，不代表64MiB标准场景；浏览器交互未执行。未运行STATIC、Agent或全项目/全模块测试。Agent部署继续暂停。

本轮证据保存在 `.ai-workspace/evidence/assisted-firstpass-20261019/`：每项目首轮日志、JUnit XML、成功项目产品管理导出、原生provider usage与只读审计、源码冻结核对及进程清理结果。凭据未写入工作区、报告或Git。

只读用量审计覆盖全部8个运行状态，396次物理尝试均与持久日志一致，无遗漏或未知usage记录；32项能力验证仍单独保留为未知。相关1551个源码、验收、样例及配置文件SHA-256与本轮开始时一致。最终只读GET三个成功网站均返回200和HTML主页；此检查不替代业务验收。

本轮两个失败均明确为FAILED，无待对账UNKNOWN，均未发布应用。输出耗尽发生在约139秒及133秒，未达到600秒期限或单次100万已知Token阈值；没有据此推断额外代码缺陷，也没有调高预算或关闭独立审批。

本轮连接与环境准备各1项通过；八项目定点验收合计6通过、2失败、0错误、0跳过。所有本轮本地验收、审计和只读诊断进程已退出；三个交付网站继续运行供用户验收，三个CLI保持按需安装状态。

收尾统计曾因PowerShell日志CRLF换行导致汇总脚本未匹配成功标记；已只在本轮临时分析脚本中归一化换行，并以原日志、JUnit和产品导出核对为6通过、2失败。该修正未改动产品或验收源码、未重跑测试，Token总额前后一致；原始日志和修正记录保留。
