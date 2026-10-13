# task-board 发布收尾只读调查：59

58轮尚未通过。只读调查确认：模型接受 HEALTHY 验证后提出 `finish`，产品随后抛出 `ApiException`，尚未返回成功进入正式 App 提交。原日志未保存具体 problem.code 或首发调用，根因仍未知；本轮没有恢复、重放、清理原 UNKNOWN 或再调用模型。

调查仅限58原任务 `4e3c7265-9e4c-4152-87d0-e3009c1630cd`、数据库 `agent-main-only-diagnostic-live-task-board-30lc0aveo` 和请求 App `wtl-agent-main-only-diagnostic-task-board-30lc1unw7`。实际事件175–180依次为 VERIFIED／HEALTHY、PUBLICATION_ASSESSMENT、FINISHED／MODEL_ACCEPTED_VERIFIED_RESULT、ERROR／ApiException、ERROR_CAUSE／OTHER、UNKNOWN；没有 CLEANED 或 DELIVERY_RESULT。

执行记录 `350c4e12-e413-43e0-8a37-8cffd5e383a2` 仍 EXECUTING／UNKNOWN，business_committed=false、cleanup_complete=false。正式 App、发布记录及该任务业务回执均为0。四个 FILE lease 中两个已释放，两个来源快照仍未释放。远端曾实际 publish／verify，App 数量0不能证明远端没有效果，也不能据此删除资源。完整业务、浏览器、重启和产品管理收尾未验收。

源码定位到 finish 的资源释放、模型命令释放、候选清理证据构建和候选清理四个阶段。现有日志把未列举 API 异常映为 OTHER，外层在已开始远端效果后返回 `DEPLOYMENT_AGENT_COMMIT_UNCONFIRMED`。finally 中原有命令释放也可能覆盖首发异常，现有证据不能证明本轮发生。终态来源阻塞是未结清证据，不能当作首因。

共三个只读尝试：首轮因辅助脚本误把后继语义5用于根任务而安全拒绝，后两轮成功。实际根任务语义4、无后继绑定，与源码 root4／child5一致；数据库版本仍1，没有迁移。使用原锁排他、IFEXISTS、ACCESS_MODE_DATA=r、AUTO_SERVER=false；没有 SQL 写、产品 Runtime、provider、SSH、恢复或其他数据库连接。三轮读取前后 MDB 与 db-access.bin 长度／SHA及原三个文件集合均一致；原 work 目录保留。

父独立核对原投影、事件顺序和双 SHA，通过且没有新数据库连接。六组 reader／launcher 的精确 PID／UTC 创建时间均已退出，正常用户 CIM 正控制通过，未人工终止。父辅助首轮把已有 work 目录混入文件集合而拒绝的记录保留，修正为实际文件集合后通过；这不是产品业务失败。

原证据保留于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-publication-finish-preflight-59/`，父核记录位于 `agent-publication-finish-parent-59/verification.json`。下一步仅补 Agent 专属、安全的收尾阶段及 API 错误元数据，先运行单一故障方法；不改变正式登记、清理顺序、UNKNOWN 和审批边界。已通过证据复用，不重复小范围全量。
