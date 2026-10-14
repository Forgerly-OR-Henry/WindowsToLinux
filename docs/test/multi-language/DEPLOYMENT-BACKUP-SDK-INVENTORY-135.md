# 135：真实 SDK 备份盘点第一层

唯一 `inventorySelectiveBackupMetadataThroughProduct` 实际 1 PASS、0 FAIL、0 ERROR、0 SKIP，1182秒；2026-10-15 01:28:22.2328012 至 01:48:09.0901348 +08。Maven/sourceFreeze均0，Task `399aba31-4d87-4f01-ab25-81926dc5e780` SUCCEEDED，实际指纹匹配授权目标。69 stat、69目录LIST是本计划逻辑SDK操作计数，不能等同物理SSH/provider请求数。

最终 inventory SHA `523401c1139921b69c1e246fa55f20bc6ff4e7aecbd88dc6cd90178abd0dbfe2`，保留224对象（177目录、47文件）、108 pending目录、16个明确未跟随的current LINK。identities有19普通文件、观测570B；model-command-operations有24普通文件、观测4368B，均尚未读取正文或证明具体归属。data实际14个下一层目录，包括12 databases和2 files；config实际24个版本目录。storage、work、release、toolchain、application-locks仍有未查范围，不能按名字排除或当可删缓存。

本轮仅证明封定计划的元数据查询完成，不证明全部范围已查清。selected空、selectedBytes0描述该元数据阶段，coverage仍UNKNOWN、backupComplete false。没有正文读取、下载、部署或旧任务恢复；没有配置或调用模型/probes的路线，原log的AI_LIVE标记0，未独立审计外部完整账单。

运行期source3299、HEAD `5893f2193d873cd0dd4f606c6f0591b6912ca381`、deployment.jar `7392678c5f84dc796ec491fd1f648778934abbb8eb0a90f97db0b093423b6d1e` 及输入冻结。原报告、log、inventory SHA和精确收尾由父核验；正常非管理员CIM确认自有PID/创建时刻全退，唯一GUID加密配置副本与UDS已清，原配置和全部调查数据库保留。

原证在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/selective-sdk-inventory-135/`。下一最小盘点是实际data14/config24目录；身份/回执小文件为保全候选，不冒称已导回。真实下载仍需保护、完整独立APP、新grant/SDK回执和SHA；数据库应优先复用现有窄handoff一致性导出，不能将朴素复制、无WAL或单纯hash作为一致性证明。必要导回及覆盖仍未完成，不能暂停或提示可重装。
