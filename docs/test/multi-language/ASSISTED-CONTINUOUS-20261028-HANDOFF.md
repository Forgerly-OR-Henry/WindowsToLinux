# ASSISTED连续首过验证：暂停与换机交接

2026-10-28，Asia/Singapore。用户明确要求“不启动第六轮，直接收尾”。目标尚未达成，按暂停保留；恢复须由用户明确提出。

## 停在什么位置

- R01至R05已结束，首次完整结果依次为4/8、7/8、4/8、7/8、7/8。R05仅FTP业务失败，其余七项完整首过且无内部生成恢复。
- **R06未启动**：没有第六轮driver、模型请求、项目尝试或远端清场。已准备文件归档为`.ai-workspace/evidence/assisted-continuous-20261028/round-06-preparation-only/`，不计新轮或失败。
- 收尾仅恢复此前已删除的生成类：skipTests package成功，实际JAR/CLASS/Mod描述符及55项类路径已核；实际chunk PUT读取300000ms，其它HTTP20000ms。没有运行新一轮业务测试。
- 主分支`main`。上传修复最终源码提交`9259ee920899449e64f8b8260364b1af511d63e2`，R05报告提交`260620df27fa624a52076e44ddd518099d069dac`；交接文档另有后续本地提交，以`git log -1`及暂停提交回执为准。**均未推送**。
- 用户论文的三项既有删除、原DOCX修改和未跟踪排版副本仍保留，未纳入本任务提交。不能用reset/clean丢弃这些工作。

## 各轮失败及处理

| 轮次 | 首次结果 | 原因与解决方案 |
| --- | --- | --- |
| R01 | 4/8 | FTP首次生命周期派发被误认历史不确定重放，修正APP/fresh grant之后的派发CAS；Asset/CSV审批HTTP断流，选择既有有界纯生成恢复并保留未知用量；Log缺原生构建前置准备，加入受控准备、严格回执和fresh probe。外部断流唯一原因未知。 |
| R02 | 7/8 | FTP审批将Agent的9参数布局套给标准构建；补清两种布局及实际有序参数事实，不改执行参数或重试完整DENY。CSV有一次内部纯生成恢复，不能称零异常。 |
| R03 | 4/8 | TaskBoard跨组件绑定上下文混淆，绑定当前计划及真实canonical准备回执；FTP慢上传增加被动进度诊断；CSV SSH窗口等待只补typed诊断，底层原因未定；Survey完整DENY与既有fd桥接不符，未强制批准或重放旧UNKNOWN。后三项不能统称已根治。 |
| R04 | 7/8 | FTP第二版分块25.1秒收满，但Java20秒先终止；下一轮仅chunk等待固定40秒，并修测试peer中断清理。补齐journalctl长JSON字段采集，原失败不改。 |
| R05 | 7/8 | Java40已生效；FTP第2块在Go绝对30秒处仅收834048/1048576B，Node随后502。修为端点局部D240/I30/A30、Node整体290秒及客户端300秒，保留完整负载、精确ACK及一次请求。物理慢供给原因仍未知。 |

完整原任务ID、逐项结果、预检/辅助失败和原始证据见[逐轮报告](ASSISTED-CONTINUOUS-20261028.md)。旧UNKNOWN不重放、不改写成功；不拼接不同轮的PASS。

## 当前修复验证与边界

修复链`3d192ed3 → 2463458f → a1ed0b8d → 9259ee92`共15文件。真实本地Go/Node/同一Java验收helper在45.180秒完整传输1MiB、1 PUT、Go恰好1提交并通过原number/SHA；真实静默300.119秒精确超时。Go24叶子、Node25具名、Java15预算场景分批通过，其中2个Go/Node场景重叠；专用集成缺省另为1 skipped，非业务PASS。双tsc和build.mjs通过，依赖lock未改。不是全suite或下一轮实机通过。

A是协作截止，不能硬中断fsync/mutex；慢上传可更久占全局锁，cancel接口可能排队。已经进入提交的取消竞争或无ACK仍未确认；Java300秒是响应读取等待，不是整个write/请求硬deadline。辅助held-read曾出现Node200/FINISH后client RST，六次预定A/B未证明唯一原因，原失败保留。零正文HTTP断流的忠实本地注入也未验证。

定点原命令、失败、退出、源码哈希和去重口径见`.ai-workspace/evidence/assisted-continuous-20261028/round-05/ud28-verification.md`。首个失败Node夹具曾仅在核身份后停止自有进程，原runner模板的naturalExit标记不权威，以cleanup审计为准；不能概括为所有批次自然退出。

## 服务器与下一轮清场

测试端点`45.192.105.213:22`，用户`root`；已授权且实测认证的指纹为`SHA256:DyqPjWTN6VdHe3atLFDCXWOa7Ur/kuK4+Km/FqK4jKg`。图片不包含SSH指纹，不能据图片独立证明主机身份。密码不写入本文。

以下是R05最后产品回执状态，暂停收尾没有再发远端操作：五个网站均STOPPED/DISABLED（FTP由原finally确认），三个CLI为INSTALLED/NOT_APPLICABLE；保留应用版本、业务数据、工具链/开发包和缓存。**服务器尚未做下一轮清场**。新机继续R06前必须重新inventory→plan→apply→独立after，并在第一项目启动前核initialReset；不是OS重装。

若发现保留候选挂载或计划漂移，按R03/R04既有守卫处理：新维护任务、fresh grant、严格身份校验、正常candidate-cleanup或静止服务后重新计划；不能force卸载、关闭漂移检查或重放原UNKNOWN。

## 换机必须保留

1. 完整工作区及`.git/`：本地未推送提交、分支和用户未提交论文不能只靠远端clone恢复。`AGENTS.md`是个人本地规则，不加入Git。
2. `.ai-workspace/progress/CURRENT.md`、对应notes和整个`.ai-workspace/evidence/assisted-continuous-20261028/`。还需保留旧工具`.ai-workspace/evidence/assisted-firstpass-20261027/FreshCredentialState.java`和`.ai-workspace/evidence/assisted-resume-20261024/tools/stop-run05/StopPriorApplication.java`，R05 runner直接引用它们。保留原日志、XML、exit、reset计划、交付JSON和哈希，不只复制汇总Markdown。
3. `src/app/db/data/`中本任务`assisted-live-*`、`static-live-maintenance-*`、`agent-live-credential-20261028-continuous-02`等隔离状态，作为原账本和UNKNOWN证据保存。它们不是新部署入口。不要为了省空间删除未知状态库。
4. 私有`test/apikey.txt`和服务器凭据通过安全渠道迁移或重新录入；不要提交Git、贴进交接、写命令行参数或复制到公开证据。

本机凭据通过`CurrentUserProtection`使用Windows当前用户DPAPI保护。复制加密库不保证新机器/Windows身份能解密，知道测试主密码也不能替代DPAPI身份。新机应保留旧库作证据，并用新的隔离状态重新录入和认证；不要覆盖02或转储其明文。`FreshCredentialState.java`只接受不存在的新`agent-live-*`目录，bootstrap使用无回显输入/子进程环境，沿用产品保护及指纹检查。若指纹变化，先核对目标和新指纹，不把旧授权当成任意密钥许可。

本任务启用了`H:/.codex/skills/gpt-6-1-sol-router/SKILL.md`。若新机继续沿用该技能，单独迁移技能并重新读取；不要假定旧机全局配置、凭据或绝对路径会自动同步。

## 新机环境、命令与接续顺序

本机已验证JDK21.0.10、Maven3.9.12、Python3.12、Go1.26.8、Node24.16.0、TypeScript5.9.3。路径分别在`E:/Program/Java/JDK21`、`E:/Program/Java/maven/apache-maven-3.9.12`、本机Codex Python缓存、`E:/Program/Go/1.26`、`E:/Program/Nodejs/24.16`。新机按实际安装位置改脚本；不改系统级设置来迁就旧路径。

已保存的`java-classpath.txt`是本机55项绝对路径，不能跨机直接照用；需按新cwd/Maven依赖和实际模块输出重新生成并核全部存在、目标CLASS provider唯一。`.ai-workspace/tmp/`不必迁移。远端构建工具仍由产品准备流程处理，不手工部署替代ASSISTED验收。

先读本文和权威进度，再执行`git status --short`、`git rev-parse HEAD`核对提交和用户修改；仅迁移不代表恢复目标。需要本地产物时，以下是已经使用的编译范围（替换Maven/JDK路径）：

```powershell
$env:JAVA_HOME = '<JDK21目录>'
& '<Maven目录>/bin/mvn.cmd' -B -ntp -pl src/mod/files,src/mod/deployment -am -DskipTests -Dmanaged.runtime.staticLive=false -Dmanaged.runtime.agentLive=false package
```

用户明确恢复后，创建全新R06证据目录/driver，适配工具路径、类路径和新的凭据状态；不要直接运行准备归档或覆盖旧label。沿用LOW/LOW、1500000已知Token监督阈值、65536输出、90分钟部署等待及上述有限上传策略。部署/审批保持独立上下文。先完成产品清场和独立复查，之后八项目各排一次：TaskBoard、FTP、Asset、CSV、Survey、Log、Directory、Binary。

现有runner控制命令为`reset inventory <label>`、`reset plan <label>`、`reset apply <label> <plan.json>`、`run <project>`及`quit`；参照R05原launch和runner，不把这些命令在暂停期间提前执行。所有Maven测试必须显式`-Dtest=...`，不默认全量。

继续验收仍仅使用测试代码，不使用Computer Use/真实UI。目标是同一新鲜冻结轮八项各自首次完成原任务、完整业务、交付及清理；FTP保持64MiB两版本、1MiB、五次完整下载SHA、10并发complete、重复/冲突/取消/持久化与选定backend停启。完整项目重试、UNKNOWN、取消或人工补救不能计首过；内部纯生成恢复单列，不能称零异常。未满足则保留失败再按用户恢复后的指示继续，不能把本次暂停标记为目标完成。

暂停的最终隐私/进程/临时目录回执位于`.ai-workspace/evidence/assisted-continuous-20261028/`下的`pause-final-privacy-audit-01.json`、`pause-final-process-cleanup-01.json`与`pause-final-commit-01.json`；核查结果以这些原回执为准，不包含凭据明文。
