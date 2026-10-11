# 当前部署 MOD 的静态真机验收

日期：2026-10-06。当前状态：五个选定网站的 STATIC 真机业务验收全部通过，模型调用均为0，服务均已停止并禁用自启。本记录与 [2026-09-20 历史验收](VERIFICATION.md)独立；本轮结果取自当前程序及本轮证据。

## 范围与执行入口

本轮只测 task-board、file-transfer、asset-lending、csv-inspector、survey-scoring 五个网站，不测单语言或三个 CLI。使用当前 `DeploymentStaticLiveAcceptanceTest`，从实际平台启用部署 MOD，再调用 `DeploymentStaticWorkflow` 默认 STATIC 入口。程序负责 SSH、环境准备、工具链、构建、发布和生命周期；验收提供固定来源、依赖选择、端口与运行审阅输入，并通过真实 HTTP 检查代表性业务、重启和持久化。验收入口禁止 AI 能力调用，不配置模型。

该入口属于代表性 HTTP/API 真机业务验收，运行后端为原生/systemd；浏览器交互、容器真机发布、本地运行器的 standard 规模和完整故障矩阵不在本轮范围。单语言与 CLI 未测试。

目标环境为一次性 Ubuntu 24.04 x86_64 服务器，root 账户经用户明确授权。秘密只通过进程环境和平台受保护秘密库传递；运行数据位于忽略的 `src/app/db/data/static-live-*`，不放入辅助证据目录。

## 已验证结果

| 检查 | 当前结果 |
| --- | --- |
| 当前 Java 21 构建、多语言确定性规划 | 通过，规划用例 1 项 |
| 当前平台 SSH 基础认证 | 通过两次 |
| 当前产品环境准备 | 通过，487.8 秒；helper protocol 14 |
| task-board 静态构建与发布 | 通过，Java 后端和 TypeScript 前端均发布并通过产品健康检查 |
| task-board 完整业务、重启、持久化及清理 | 修复后复跑通过，真实 HTTP 创建记录与 STOP/START/RESTART 后持久化通过，modelCalls=0，最终 STOPPED/DISABLED；首轮失败证据保留 |
| file-transfer | 完整通过；真实 HTTP 文件业务、跨语言调用、重启与持久化均通过，modelCalls=0，最终 STOPPED/DISABLED。首轮 UNKNOWN 已由原任务核对明确 FAILED 结清 |
| asset-lending | 完整通过；资产/借用/审批/出借/归还业务与重启持久化通过，modelCalls=0，最终 STOPPED/DISABLED |
| csv-inspector | 完整通过；补齐 FILE 与 seed 输入后，真实三行分析、六项问题、一行有效、工作进程重启与报告持久化均通过，modelCalls=0，最终 STOPPED/DISABLED |
| survey-scoring | 完整通过；跨语言问卷评分、重启与持久化通过，modelCalls=0，最终 STOPPED/DISABLED |
| 生命周期修复相关本机回归 | 43 项通过，失败、错误、跳过均为 0 |
| MOD 版本身份规则与恢复相关本机回归 | 126 项最终通过，失败、错误、跳过均为 0；不同版本仍拒绝恢复 |
| 整图回滚收尾相关本机回归 | 27 项最终通过，包含首组件失败收尾、未发布组件状态变化保留 UNKNOWN 与原任务重启恢复 |
| 修复后原任务恢复 | 通过；原部分停止与排队查询明确 FAILED 结清，原锁释放，旧应用由程序 STOP 并禁用自启 |
| 最终远端只读收尾 | 通过；活动测试/构建单元0、应用/构建进程0、测试监听端口0，输出完整无截断 |

最终五站对应：task-board、asset-lending、survey-scoring 使用 `websites-2.log`，file-transfer 使用 `websites-3.log`，csv-inspector 使用 `websites-5.log`。对应 XML 与通过/零模型调用/停机标记合并为 `websites-final-summary.json`：5/5通过、5/5零模型调用、5/5停止且禁用自启。

首轮受限环境的 DPAPI 检查失败后，在实际当前用户环境执行成功；未修改 DPAPI 实现。APT 安全源曾等待连接，后续访问与原安装成功，未修改网络实现。UFW 服务活动与过滤未启用是不同观测，不互相替代。

## 已修复的程序问题

1. 远端 helper 成功停止服务时会先输出 `STOP_BEFORE_*`、`STOP_AFTER_*` 诊断，再输出 `LIFECYCLE=stop`。客户端此前要求 stdout 整串只含完成行，错误返回 UNKNOWN。`DeploymentPublishChannel` 现在允许诊断前缀，但要求完成行唯一、完整、位于末尾；仍要求退出码 0、无截断，且重新验证归属和实际停止状态。普通生命周期与更新前停止旧发布均修复。
2. 首组件停止后丢失有效确认时，原任务核对此前不能结束已确认的部分停止。`DeploymentApplicationRunner` 现仅对原 STOP 的完整所属观测进行核对：已尝试组件必须 STOPPED，未尝试组件必须保持原态，自启必须保持原态；符合条件时记录明确的部分失败，不发送新动作。状态、归属或发布变化仍保留 UNKNOWN；RESTART 与自启恢复规则保持原合同。
3. 整图首组件健康失败后，未发布的其他组件若初始没有旧发布，也没有旧快照。原收尾仍尝试丢弃不存在的快照，导致 helper 拒绝、原任务 UNKNOWN。修复改为先只读验证该组件仍无当前发布、快照、维护标记、活动构建、单元、容器和留存，再跳过空快照清理；状态变化时继续保留原占用。27项相关本机回归通过；文件传输原真机任务已由产品恢复为明确 FAILED、释放占用，正常重启应用列表为空。

文件传输及 CSV 网站还需持久化文件目录。首轮固定验收输入漏传了 STATIC 审阅页面支持的 FILE 绑定，已补齐 DEFAULT/data/rw 输入；CSV 还需要通过已有 FILE 契约传入示例声明的 `seed` 初始目录，否则独立组件归档缺少运行时回填引用的外部 samples。以上是验收输入修正，不是增加静态自动识别能力。Go 与 PHP 的只读文件系统错误通过产品 Linux 只读能力确认；CSV 缺少数据集通过真实业务响应确认。

回归包含合法诊断、错误/重复/嵌入/尾随回执、非零退出、截断、退出缺失、最终状态不符、部分停止已确认、停止未确认、未尝试组件变化，以及既有自启和发布身份边界。

## 原故障、恢复与保留状态

首轮应用为 `wtl-static-task-board-4w0uvr4kc`，原 STOP 任务为 `2cc958ec-a352-4b00-99c8-ef0ab780c348`；原受保护数据为 `src/app/db/data/static-live-task-board-4w0uujzeg`。原停止任务经修复后的同版本程序只读核对，按部分停止 FAILED 结清，旧排队查询也明确 FAILED 结清；原数据库保留。恢复会话正常释放并重启后，程序将旧应用完整停止、禁用自启，最终 STOPPED/DISABLED。核对原动作期间没有重发原停止或部署。

原恢复尝试因代码指纹变化被平台拒绝，报告 `MOD_CATALOG_BUILD_IN_USE` 和 `MOD_RECOVERY_UNAVAILABLE`。按用户决定改为 Mod ID 与精确版本号核验，允许本版修复构建核对原任务；原任务摘要与数据库保留，Schema/数据代次、依赖、路径及内容完整性约束保留。126项相关本机回归、原任务真机恢复和服务清理均通过；本轮保持 `1.1.0-dev.3`，未更新版本号。

文件传输首轮因目录绑定遗漏而启动失败，回滚成功、收尾 UNKNOWN；原任务 `54124d1a-4874-4cd1-a79e-bcf2eb29fb49` 已经产品核对明确 FAILED 结清。CSV 首轮目录遗漏导致健康失败，回滚清理成功；后续一次构建前 SSH 预检返回 UNKNOWN，无退出码与输出，原任务 `cc1da6e2-da03-4c46-bb69-25c9c6bfb42e` 已由产品核对 FAILED 结清，正常重启应用列表为空。该次命令的具体原因未确认，后续未复现；没有以新任务重放未知操作，也没有据此改网络实现。之后发布成功但业务报告数据集缺失，补齐声明的 seed 输入后保持原断言，最终分析、重启与清理全部通过。

后续兼容性声明只记录在 [MOD 版本规则](../../Mod.md#mod-identity-version)：未来由配置声明可恢复的旧版本范围，本次不实现，当前不同版本仍拒绝恢复；整轮任务完成时再次提醒此待办。

## 复跑与证据

在 Java 21 的实际当前用户环境运行 Maven；SSH 密码通过 `WINDOWSTOLINUX_TEST_SSH_PASSWORD` 由调用环境提供，不能写进命令参数或仓库文件。启动真机测试需要以下显式开关；普通 Maven 测试不会连接服务器。

```powershell
mvn.cmd -B -ntp -pl src/mod/deployment -am test `
  '-Dtest=DeploymentStaticLiveAcceptanceTest#deploysWebsitesThroughStaticEntry' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmanaged.runtime.staticLive=true' `
  '-Dmanaged.ssh.host=<authorized-host>' '-Dmanaged.ssh.user=root' `
  '-Dmanaged.ssh.fingerprint=<verified-wire-key-fingerprint>'
```

此命令应在原未知任务被正常核对后执行；不能跳过原任务占用。首次连接、环境准备与原任务恢复使用同一类中的独立方法。

保留证据在 `.ai-workspace/progress/static-live-evidence/`：连接/环境、各轮网站日志及 XML、三个原任务恢复、43项生命周期回归、126项身份/恢复回归、27项回滚收尾回归与格式检查。最终汇总为 `websites-final-summary.json`、`mod-version-regression-summary.json`、`storage-rollback-regression-summary.json` 和 `final-remote-closure.json`；全局远端只读核对见 `observation-final-1.log`。失败日志与受保护运行数据保留；未提交或推送代码。
