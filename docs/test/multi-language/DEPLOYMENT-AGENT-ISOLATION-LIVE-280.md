# File Transfer 隔离验收：首次准入拒绝（280）

本轮是失败的 FULL 尝试，尚未进入业务、浏览器或 STOP/DISABLE 验收，不能计为完整通过。原238及其资源、两次独立审批拒绝证据继续保留，本轮没有恢复或重放它。

## 实际调用与结果

- 实现交付：本地 `a31e40b57833b4eba5642b4a86206f1a7be1f8a1`，未推送；先前10个定点方法最终全P、package0。
- 唯一产品调用 `file-transfer-isolated-280-01`，实际UTC10:04:16.4409033Z—10:07:08.8850442Z，进程退出1；JUnit1F/0E/0Skip，167秒。累计10次历史FULL、0个完整BUSINESS通过，CSV未启动。
- 新Runtime：`src/app/db/data/agent-live-file-transfer-ufjfmpaf4`；App：`wtl-agent-file-transfer-ufjfoeitg`；Task：`4255f545-7acb-4b47-a331-45c977d086dd`。
- 模型先根据新观察的容量拒绝重新规划；已有共享helper经独立APP和新grant实机确认READY。隔离probe亦独立APP APPROVE，执行实际SUCCEEDED/0、不截断；这不是审批DENY。

probe action为`54a6461f-fb1b-40c3-9408-5d6bc95157bd`，sourceVersion为`05f8cbbd130059142b5394155c99508ab7bdd39ba9c06601a92327c550dc56c2`。完整结构回执为 **REFUSED / DISK_SPACE**，没有READY：

| 观测 | 实际值 |
| --- | ---: |
| paths / systemd units / ports checked | 17 / 4 / 2 |
| 文件系统device | 64769 |
| availableBytes | 5264719872 |
| requiredBytes | 4227858432 |
| reserveBytes | 1073741824 |
| 合计需求 | 5301600256 |
| 差额 | 36880384 |

隔离预算包含额外64MiB的现成工具链PROBE卷，原计划容量选择未覆盖这个差额。memoryAvailableBytes/cpuCount尚未观测；原238有限24行保护元数据已取得，但本轮未准入，因此没有事后保护比较，不能称原238已验证结清。

实际反馈链在处理该确认拒绝时抛通用异常，随后PREPARE被记为UNKNOWN，产品goal BLOCKED；JUnit按原验收断言失败，没有放宽断言。日志显示2个source-snapshot FILE blockers，但这不是完整资源计数。

随后固定Root/Task/App的existing-only reader实际javac0/read0：平台Task和Mod Task均UNKNOWN；资源688行，683已释放、5未释放（2 FILE、MOD/SCHEMA/SERVER各1）。正式App/组件/发布/操作回执均0，正式execution0。完整54行事件与生产公开ActionLog读取逐字相同，public身份校验通过；包含1个PREPARE_EXECUTING，无其RESULT、无CANDIDATE和AGENT_DATABASE_INDEX记录。0派发索引不等于远端没有执行任何动作，实际只读probe已确实派发，不因此改判或结清。

reader仅H2 READONLY/SELECT、未打开Runtime/TaskContext、credential rows 0，DB/bootstrap/既有空锁在持锁关闭后和总关闭后属性相同，未做数据库内容hash。原计划身份由PLAN40及WORKSPACE_QUOTA41/42确认：backend unit为`wtl-agent-file-transfer-ufjfoeitg-backend`、workspace1476395008B；web unit为`wtl-agent-file-transfer-ufjfoeitg-web`、workspace603979776B；只代表接受过的计划。没有候选身份记录，不猜候选名。下轮可信保护集据此纳入280，使用新端口49282/49283，238与280都保留UNKNOWN。

## 失败窗口用量

离线仅统计实际stdout中的25个physical generation attempts，非动作或审批决策次数。已知input/output/cache字段25项均有值：

| 角色 | attempts | input | output | hit |
| --- | ---: | ---: | ---: | ---: |
| DEP | 12 | 339136 | 10459 | 212480 |
| APP | 13 | 159800 | 2668 | 121728 |
| 合计 | 25 | 498936 | 13127 | 334208 |

已知IO512063，APP IO/(DEP+APP IO)=**31.7281%**；输入加权hit/input=**66.9841%**，miss164728。DEP12项reasoning已知且属于output，不重复相加；APP13项reasoning未知。完整provider generation inventory、未观察的配置/probe、HTTP及账单覆盖未知，不宣称完整账单。本轮与纯恢复APP100%窗口分别统计，也不能替代两个完成项目的最终指标。

## 收尾与继续范围

实际进程已退出，CIM匹配Java0、terminated0；核绝对工作区边界、owner/nonce/currentSID及无重解析点后，仅清理本轮f280a/u280a和临时加密凭据副本。新UNKNOWN Runtime、原始日志/XML/回执及旧238证据保留。没有280attempt02或CSV并行调用。

按用户“同类采用推荐、Agent调试自主完成”的授权，后续保留280 UNKNOWN并修复Agent反馈：只有严格解析当前真实完整回执得到的REFUSED才可作为明确未准入事实回主模型重新规划；传输异常、不完整、UNKNOWN仍保持未知，不减预算、不伪造free/READY，不改写本轮历史。不新增一次恢复或审批代际。

证据：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-isolated-acceptance-280/` 下 `file-transfer-isolated-280-01/`、`usage280.json`、`live-cleanup.json`；随后账本核对证据保存在281的reader目录。两个完整验收完成后才暂停并报告最终指标。
