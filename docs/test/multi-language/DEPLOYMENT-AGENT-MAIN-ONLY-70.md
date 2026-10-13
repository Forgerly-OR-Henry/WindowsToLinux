# task-board 纯主 Agent 真实验收 70：发布健康检查失败

唯一真实轮 `task-board-main-only-70-01` 自然结束为失败，明确公开故障码 `DEPLOYMENT_HEALTH_FAILED`。2026-10-14 02:02:55.3631183 至 02:25:46.7326123（UTC+8）运行产品入口 `DeploymentStaticLiveAcceptanceTest#deploysWebsiteThroughMainAgentDiagnostic`，Maven 退出 1；原 XML **1 FAIL / 0 ERROR / 0 SKIP**，1363.921 秒。本轮未获得正式部署成功，也未进入真实业务、浏览器及重启持久化验证。

原迁移工作区的 3296 项源码在运行前后完全等于 69 封口原像，基于本地提交 `0196d28453efc97f2792966319d34351f95ce1ac` 和 JAR `856953499723e0e8a42823b97d689b7c313a5631315f2488b665ccafc9f8b767`；没有中途源码/Skill 修改。新任务使用 task-board 来源并自主识别、重评工作区计划及排查宿主环境，没有重放原冻结错误计划。Root 只监控，不接手 SSH、不补充逐步部署答案。

实际 49 次 LANGUAGE 物理生成；计划 13 次，host_command 1 次，Skill 4 次，command 12 次，数据库检查/来源选择、seal 2 次、deliver 1 次、contract 4 次、publish 1 次、finish_failure 1 次。观察到远端 BUILD SUCCESS，并经过 PLANNED/PREPARED/SEALED/PUBLICATION_ATTEMPTED；这些阶段不能代替真实应用通过。模型自行调用 finish_failure，此后没有 continue_deployment 调用或新后继任务。Root 未触发原恢复、删除记录或再次付费部署。

72 对同一原数据库进行了定点只读核查。read-01 在在线任务身份检查拒绝；保留原失败，修正辅助读取器的在线任务假设后，read-02 于 2026-10-14 03:09:12.7739004 至 03:09:16.8437166（UTC+8）退出 0。原在线任务行已不存在，产品现有归档读取器验证 task/owner/version/type 全部匹配，结论为 ENDED/FAILED，执行语义 4、数据库版本仍为 1。唯一 execution `d2c03504-b145-4e9d-90be-08c8d5520a52` 为 FINISHED/FAILED、businessCommitted=false、cleanupComplete=true；正式 App、release、operation receipt 和该任务资源记录均为 0。原事件 196 为 GRAPH_ROLLBACK_RESULT/RESTORED，217 为 AI_PUBLICATION_FAILED_CLEANED/ABSENT。由原产品记录确认回滚及失败收尾，不能据此声称服务器所有磁盘资源已清空或完整真实恢复验收通过。

原发布模板包括后端和前端两个 MANAGED_PROCESS/DAEMON 组件，分别声明 HTTP 18101 和 47400、预期 200、超时 60 秒，整应用检查也使用 47400。SQLite 无种子或初始化文件，文件种子和 secret 引用数量均为 0，后继资源条件为 true；该布尔值不证明剩余预算及 source capsule 资格。没有选中固定健康诊断日志行，原健康命令输出和实际 HTTP 响应未在本投影中取得，具体健康失败原因仍未知。源码中 publicationNextStep 漏列已提供的 continue_deployment 是另一个已确认的提示不一致，不能直接归因全部停止或健康故障。

两次读取只使用固定原任务的 SELECT 和受保护配置；第二次额外设置连接会话 schema，未执行持久 DML/DDL、Runtime 或 SSH。原 MDB/access 文件长度与双 SHA、目录文件集、3296 项源码和 JAR 前后不变，读取进程已退出。read-02 安全投影 SHA-256 为 `ec72956edb644a042c8bd10bf3b9c956cd733f63fd27046f4be977c6452a81b5`，原调查数据库及第一版读取失败证据保留。

Runtime 关闭后的审批 attempts/providerCalls/tokens 全部 **0**，纯主 Agent 诊断授权整链生效，仍非独立审批验收通过。49 次已知 provider usage：input 2897278、output 177870、合计 **3075148 Token**；cache hit 1468928、miss 1428350，三字段一致，输入缓存命中率 **50.7003%**；reasoning 145206 已包含在 output。两次能力探针的 usage 与 transport call count 均 UNKNOWN，另列，不按零计。该失败调试轮不混入最终冻结八项目综合轮。

原日志 SHA-256 为 `1f5c0c6fd6e4fb8abd34a5a8d6597a3beeb56b296f25d1b3661abc3fb04b1542`；原 XML/TXT 的哈希、selector、时间戳和运行窗口已核。正常用户 CIM 正控制及两快照合并的 7 个精确 PID/UTC 创建时间均为退出，未人工终止。仅本次 GUID 受保护配置副本在核定工作区路径清理，原 clean 配置双 SHA 未变，调查数据库删除零。

证据位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/task-board-main-only-70-01/`、同名日志及 `main-only-full-business-live-70/`。实际状态根为 `agent-main-only-diagnostic-live-task-board-87nf3lrb0`，请求 App 为 `wtl-agent-main-only-diagnostic-task-board-87nf52gu4`，公开目标端口 47400。原 58 UNKNOWN、66 失败及其他恢复/目录待验证据保留。下一步仍先解决 task-board 实际阻塞，不并行部署其他项目，不为同一未知原因重复付费取证。
