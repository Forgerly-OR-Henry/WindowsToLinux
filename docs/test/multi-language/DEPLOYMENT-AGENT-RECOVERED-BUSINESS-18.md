# 原 Agent 恢复及真实业务验收入口

接续[收尾修复17](DEPLOYMENT-AGENT-COMMAND-FINISH-17.md)，本阶段仅修正真实验收夹具，产品恢复、独立审批、配置和 Skill 均不修改。随后原16产品恢复已完成业务提交与候选清理，但历史归档仍阻塞，平台任务保持 UNKNOWN；当前按用户要求暂停，实际结果见下文。

原 main-only 测试库现在可从已有 reconcile 入口进入；仍严格限定产品数据根，保留原 task/server/模型绑定和全部恢复守卫。此入口启用 AI，使用原已验证审批用途，以独立审批会话审阅当前恢复命令；不进入诊断关闭审批夹具，不重配模型或发起能力探测。main-only 原部署仍按诊断记录，恢复审批不会反向授权原部署。

恢复后的业务入口先查原成功平台归档，按 Agent 原 taskType 校验，并通过 usageForCompletedTask 取得原完整应用身份和已审阅浏览器端点，精确匹配原 App/publicUrl。先运行真实业务写入、受管停止/启动/重启与持久化验证，再等待实际浏览器验收；ready/result 精确绑定 application 和 URL，结束后产品停止及禁用自启。

Agent 的业务客户端与浏览器均读取真实 runtime-config.json 的 apiBase，严格按实际 TypeScript 的 base+path 语义解析，保留尾斜杠形成的原路径，并拒绝 protocol-relative 外来主机。去掉夹具中必须直连前端端口加一的冲突要求，保留授权主机/协议和跨源预检、响应 CORS 校验。STATIC/ASSISTED 原显式端口路径保留。该改动约束验收身份和真实业务结果，没有向部署模型指定工具调用或业务步骤。

3 个直接 loopback HTTP 参数最新通过：同源、同源路径前缀、独立非相邻 API 端口；每项实际 GET/POST，跨源预检，并确认异主机/协议在发请求前拒绝。首次非相邻端口夹具恰好分配相邻端口，失败在请求前；第二轮通过后在封口核对 TS 消费者发现尾斜杠语义不一致，撤回尚未提交/启动 live 的中间补丁及父证明并保留；修正后 focused-03 同一直接方法3参数实际通过，3279源码运行冻结零变化。两个 Java 文件限定格式检查通过，自有验证进程为零。不是全模块、真实恢复或浏览器通过。

3279 源码/配置/Skill 文件仅两个测试 owner 改变，非 owner、新增/删除为零。父独立核 actual XML、前后像与补丁正反向应用后的字节。[精确增量补丁](patches/DEPLOYMENT-AGENT-RECOVERED-BUSINESS-18.patch) SHA256 `9a73c12ab894335a05f0de4055c784374deb9231e36ef3efcaf605c59ec15a68`；独立 HTTP 测试直接提交，含既有迁移的 live 夹具只交付本次增量。补丁基于原工作区，未证明干净 checkout 可独立构建。

原16的额外只读账本核对：backend/frontend 的 WORKSPACE_QUOTA/CANDIDATE_QUOTA 均为1207959552字节（1.125 GiB），AUTO、主机保留1073741824字节，业务重规划保持同值；原库 SHA 前后不变。它补齐本轮 Agent 实际选额记录，三模式物理服务器选额和在线扩缩容不因此称为通过。已保存 live-deployment 同时具有经验证的部署/审批用途，不需要新配置探测。

下一步实际原16产品恢复，按前后 provider usage 记录 ID 差集单列用量，再完成绑定原 App 的浏览器业务；不重放原部署，不回填旧 UNKNOWN。真实成功历史清理、完整双 Agent 部署、非默认真机及重启、其余七项目和最终冻结八项目仍需实际验收。


## 2026-10-13 原16真实恢复与网络切换暂停

task-board-recovery16-01 从原任务产品 reconcile 执行真实独立审批；2026-10-13 01:46:40+08结束，产品入口1FAIL/0error-skip、360.2秒，3279源码/Skill运行冻结零变化。没有重放build/publish/主模型决策或在运行中修改源码。主only原部署仍是诊断，不反向计作完整双Agent部署成功。

本轮重新核实原发布健康并完成真实业务提交、候选清理及finishKnown：原formal execution 1cf87400-2b94-4672-a6e2-28d1cdf6217c已FINISHED/SUCCEEDED，businessCommitted=true、cleanupComplete=true，结果DEPLOYMENT_PUBLICATION_COMMITTED；账本新增AI_PUBLICATION_CLEANED/AI_RECOVERY_SETTLED/STATE SUCCEEDED。正式App1/组件2/发布1，原正式配置与身份保留。原model-command checkpoint仍是两个ENDED/exit0且已释放；不是把旧UNKNOWN改写成原部署成功。

平台task dc79c361-80f1-467a-8d36-4935ff4c8355仍UNKNOWN，仅Business result requires explicit reconciliation阻塞；Agent/执行/平台资源明细仍保留，operation结果摘要0、资源记录总数5569。原155 DELIVERY_EXECUTING没有配对DELIVERY_RESULT，effectsSettled=false；HistoryArchive的严格结清门因此阻断归档，产品报告TASK_RECOVERY_FAILED。不能据已有业务提交和候选清理直接删历史或宣称完整恢复成功。恢复后的浏览器/业务验收及产品停止禁用尚未启动，仍未证明前后端完整业务或独立双Agent部署。

只读post审计两次主DB SHA前后均56376364577479F161D2076879CBDF07810A5B101CAC5C4DD1B4B8217B36E425，锁释放，未初始化Runtime/API/SSH。此前未恢复成功的原13/14/15、原373正式业务及调查均保持。

provider用量按实际record-ID差集：before33/after72，新52、旧13被有界日志轮换，不能用净增39当新调用数。52捕获响应全部APPROVAL，input666816/output38997/合计705813，cache hit282880/miss383936、已知输入命中率42.42%；reasoning23161是输出子集。实际单次上限8192，低于原部署普通32768；没有新配置探测，未捕获transport尝试仍未知。本轮恢复单列，不重复计原33或混最终冻结八项目。

按用户“找合适时机暂停以切换网络”，在本轮结束、结果/用量保存和进程核对后暂停，不启动browser/business16或后续部署。01:48:45正常用户CIM自有临时验证进程0，用户既有IDE未终止；部署App与持久业务身份保留。下次用户明确恢复后，先核网络/工具链/源冻/原任务实际状态，只修成功恢复的原外层delivery结清记录这一实际阻塞，严格保留未结内层、外来记录及旧UNKNOWN，不通用忽略effectsSettled或伪造旧DELIVERY_RESULT/NOT_SENT；从产品入口结清并验证历史清除，再完成真实浏览器与业务/生命周期验收。完整八项目、非默认目录真机和Linux重启、最终冻结新服务器及缓存/审批比例仍待验收。
