# 新对话交接提示词

以下整段可直接复制到新对话。最新本地提交标识以CURRENT顶部及实际Git为准；本文件与232验收记录同批提交，未推送。

```text
[$gpt-6-1-sol-router](H:\.codex\skills\gpt-6-1-sol-router\SKILL.md)
在 I:\Program\WindowsToLinux 恢复并继续 WindowsToLinux Agent 部署验收。上个对话因上下文容量问题，在完成232轮后主动交接；先核实际状态，再继续，不直接盲启动付费部署。

先读：
1. 当前 AGENTS.md。
2. .ai-workspace/progress/CURRENT.md 顶部最新状态，勿扫描全部历史。
3. docs/test/multi-language/DEPLOYMENT-AGENT-ORIGINAL194-SETTLED-232.md。
4. DEPLOYMENT-MODEL-COMMAND-RECEIPT-CONTRACT-222.md、DEPLOYMENT-AGENT-FEEDBACK-STORAGE-LIVE-221.md；必要时再读DEPLOYMENT-AGENT-CREATED-STAGE-RECOVERY-231.md和DEPLOYMENT-DIRECTORY-CONFIG-531.md，均在同一docs目录。

当前真实状态：
- 232唯一产品reconcilesOriginalTaskThroughProduct真实1P0F0E0Skip，Maven/before/after均0。原task-board Task a67e3b89-d04e-49a7-8b5e-20ed93803d53已从UNKNOWN结为ENDED/FAILED并正式归档；FAILED是原失败部署结果，不是恢复失败或业务成功。正式宿主摘要blockers=0；原平台task/resource、Mod task/event/execution均0；唯一AGENT/FAILED操作回执保留。原App wtl-agent-task-board-q3k1qalz8的应用/组件/发布/committed execution均0。切勿再把194当未结任务付费重试。
- 232恢复后的原runtime为src/app/db/data/agent-live-task-board-q3k1ohhos，DB10416128字节/SHA dd5277baee4f887183564407ee64684345a4a08562925ff91cc9d138c674492e；358字节db-access.bin的SHA仍886b792519a8fe8ed0dc5ac894eef95c3ca1856617f0448ccafe3bba4b133f8e。不回滚旧4319库。约76MB必要私有备份保留在test/data，准确目录和receipt见232证据，不重拷整个Runtime/远端23GB。
- 实际源码3310项，与231 implementation/final-after-manifest.json同值，manifest SHA30569190ae41ff690f56ebc901d7fb0397fd3a42d50161a629a8fc3fe3f4cf68。deployment.jar3755629字节/SHA1299e3225748d0019a60097a610990c4dbee0613e9812a5ea4069066dd050ca2。其余5JAR及完整来源绑定见231 package after-metadata和232 before/after-metadata；54个运行CP入口/3210文件为静态绑定，不是JVM CodeSource。
- 实现提交a84293be852f8309c4d0a7a3c97d3d3eb96f2298，232仅交付文档和本交接文件，最新提交查CURRENT/Git。工作树仍有大量原迁移和其它任务改动，部分实际源码不在HEAD；不得清空、覆盖、reset/clean或切到缺源码的干净工作树。提交隔离本任务增量，默认不push。
- 复用231的48个已通过定点用例和skipTests打包。232仅验证辅助工具：诊断47pure消费者；正式归档工具19内存H2消费者+3纯绑定。本轮未改产品源码/配置或重打包。首个事后reader在EXACT_PLATFORM_TASK安全失败，随后新的正式归档reader真实只读成功；旧工具、失败证据、原97守门均保留，不从旧metadata补CREATED或重建已依法删除明细。
- 本轮恢复26个generation已知378086Token，cache75.7581%、APP100%；reasoning26项未知，完整HTTP/账单未知。纯恢复只有审批生成，不能混入两项目FULL趋势。产品和两个reader/closer共21组已观察精确PID身份已退出，自有TMP/UDS已清，所有执行binding已撤回false，无后台验证进程。229/230/231及232封存证据不得改写。

接下来的范围与顺序：
- 仅CSV Inspector和File Transfer两个已选项目，严格一个一个部署和调试，不同时调。两者累计7次FULL（208/209/211/214/216/218/221），零完整业务通过。最近File Transfer 221因容量及回执查询审批拒绝而UNKNOWN；222仅本地修复有限回执查询，尚无后续FULL实服验证。
- 194旧资源恢复前置已完成。先重新核来源、配置、Git/JAR、工具链及当前产品连接状态，复用有效证据。建议先续File Transfer这一条链；容量由新产品规划重新观察，不按旧盘点或归档声明推算可用字节，也不固定旧计划/端口/API/URL/CORS/监听。
- 部署模型和独立审批模型自主闭环，工具由模型自行选择，可CLI部署。你负责监控及按实际阻塞修代码/Skill；不得Root直接SSH接管部署、逐步给模型命令或答案。每个完整具体动作/修正命令都独立完整审批并取得新grant，不复用批准或讨论绕监管。
- 两项目分别完成调整、前后端真实业务、真实浏览器及产品STOP/DISABLE收尾后，分别统计FULL审批用量、缓存与已知/未知。缓存高于60%可继续；审批若仍明显偏高，暂停并与我讨论，不扩第三项目或无限优化。当前纯恢复APP100%不触发这个业务条件。
- 最终冻结八项目/全新服务器无中途改代码复验、非默认目录、整机重启及其它模式动态容量仍未验证。STATIC/ASSISTED/AGENT独立编排；共享改动要有实际依据，模式专属逻辑放对应包，安全条件不因通过而放宽。

授权与数据边界：
- SSH45.192.105.213:22/root，重装后已明确授权信任SHA256:sCRc3ICCT9OMtsaGxjciDIQd0NMVO5/+pDe/fNMgRYc，旧ioa…指纹作废。凭据只从现有受保护产品配置取，不进入提示词、文档、模型正文或日志；DPAPI需正常用户环境，沙箱失败不当服务器失败。身份再次变化再询问。
- 真人已明确授权task-board及其余7项目源码/样例及必要修正版、部署指令、观测/独立审批证据，按需发往https://api.deepseek.com/chat/completions、deepseek-flash，供串行验收及冻结后复验；密码/API密钥不作为模型正文发送。授权桥与当轮来源见207及221相关证据，不重复询问已授权范围，不擅改出站目标。
- DB保持版本1，部署中对象不进完成App表。仅在原任务真实恢复成功、资源回执全结清及必要身份由正式记录承接后按依赖归档可删明细；其它UNKNOWN/FAILED/拒绝/占用/调查证据继续保留，不能整表清除。197/201/旧58/74/116等不碰运气付费重试，不按名称或路径直接删远端资源。
- 只跑直接相关具体方法，失败只修并重跑失败/直接受影响场景，编译打包显式skipTests。reasoning属于output不重复计数，已知未知分开，不补付费探测。阶段完成按AGENTS精确隔离本地提交、不默认push，停自有进程、清自有临时目录、更新CURRENT。

232证据根：.ai-workspace/agent-live-evidence/agent-autonomy-20261012/original194-product-stage-observation-232/。入口stage-final/delivery、parent-terminal-audit232、parent-archive-verification232、parent-final-process-closure232和provider-usage232；严谨区分定点通过、产品恢复通过、正式归档通过与尚未业务验收。不要重跑232恢复或已完成的reader。
```
