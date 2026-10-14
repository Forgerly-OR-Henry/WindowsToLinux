# 174：重装前必要数据选择性导回与恢复校验完成

2026-10-15（UTC+08），本阶段必要保全完成，`test/data/README.md` 与 `backup-manifest.json` 已标记 READY。用户可以手动重装 Linux 验收服务器；后续等待新连接信息和主机身份。本阶段没有启动新部署，不能视为 task-board 完整独立审批业务验收或最终八项目验收通过。

## 实际保全范围

| 有效本地目录 | 内容 | 原归档字节 | DPAPI密文字节 | 实际恢复校验 |
|---|---|---:|---:|---|
| `test/data/backup-snapshot-download-163-01/` | 12个独立SQLite在线快照及14份回执 | 757760 | 757990 | 159：逐库integrity=ok、FK=0，原102业务持久记录及浏览器操作记录匹配 |
| `test/data/backup-context-download-173-01/` | 310个必要配置、原身份与操作回执文件及26份回执 | 399360 | 399590 | 168：311个USTAR成员、310件长度与SHA、冻结来源映射全部通过 |

两个原归档合计1157120字节（约1.10MiB），密文合计1157580字节；回执另计。配置原件内容合计28725字节，含67个合法空文件。没有导回约23GiB旧工作区映像、依赖与构建输出、完整旧执行环境或全部模型改写源码；不声称这些排除项都能逐字节重建。144实际状态根主要为旧工作区映像，不能仅按名称全部认作可删残留；本次没有以删除旧资源代替保全。

数据库是各自独立的在线快照，不承诺跨库同一时刻；配置按单件稳定读取，不承诺跨文件原子快照。当前Windows用户DPAPI及私有DACL保护密文，请保留这个Windows用户及其保护环境。恢复须重新绑定新主机和正式业务身份，不自动回放旧锁、身份或操作回执。

## 真实产品执行与验真

148通过产品独立审批、新grant和SDK命令形成12库快照归档；163实际受保护下载通过，随后159在正常非管理员用户环境仅内存解密并逐库验真。此有效证据复用，没有重导快照或重下数据库。

167通过产品独立审批、新grant和SDK命令形成配置归档，160实际SDK FileInfo元数据随后通过。173复用这份399360字节有效归档；重新观测源stat及内容SHA，以172新增完整producer链、当前私有目标保护证明和历史DPAPI能力重新送审，未复用旧DENY或grant。

173唯一实际 selector 为 `DeploymentStaticLiveAcceptanceTest#downloadSelectiveBackupThroughProduct`，**1 PASS、0 FAIL、0 ERROR、0 SKIP，84.472s，Maven0/sourceFreeze0**。Task `f5d3715e-3944-43a6-b8d3-3c1a90744a51` SUCCEEDED；独立APP APPROVE，新grant `039c2a2d-0e89-4b6e-9349-2b10039fcc74`。SDK下载回执的路径、399360字节及SHA与原167归档一致；CurrentUser DPAPI CREATE_NEW落盘后往返校验通过，按实际文件身份删除本轮自有明文，staging为空。

168在同一正常Windows用户环境仅执行一次内存解密，`nativeUnprotectCalls=1`。311个USTAR普通成员、310个原件、28725字节、逐件SHA和冻结metadata映射全部通过。没有创建解密业务文件、输出配置值、运行Runtime、调用provider或访问SSH；不宣称所有库内部副本均可安全擦除。正式验真工具 `test/tools/backup/verify_backup_memory.py` SHA仍为 `823a64b613aeabb4a54ecdaa37e20469543b9118758d6b31c8b115fc43c345ec`，复用已通过的149/153公开定点验证，本阶段未重复这些测试。

174仅在父核173原26份记录、XML/TXT、source前后及168实际PASS后，正常用户CreateNew复制26份非秘密回执并生成配置清单；未再次解密或访问服务器。随后核两份密文与40份回执长度/SHA，保留原阶段清单，更新当前清单和根说明。没有执行全量测试。

## 当前清单与原始证据

| 对象 | SHA-256 |
|---|---|
| 数据库密文757990B | `5c004750951c972294d071b29bd8248853e7847984b15a2b7a313164bf910a59` |
| 配置密文399590B | `7b6b44d650169d1d56c67aa253d8cca49705940b0d846ed8250bcc52aaf5861d` |
| 数据库原归档757760B | `ba95e5de3cde6cd2294df3ac520d590aeb65e2b80a74c00a7aa60b9da9fc2305` |
| 配置原归档399360B | `932bc3754217ff4aa4bfeaf458d96e9140834e989047a21c001a0529a13276f2` |
| 159实际数据库报告 | `6ef6077b834ca3e890bcd6bc94b2092bc93263a207591fb2f4de02f4f728608d` |
| 168实际配置报告 | `57070c22f572959988f128e32fa7abcd8bb2f353d9e6968336e1a7e52c0af710` |
| 173原成功封存 | `933fcc6a2b00495b37cd7415caaa8d8d6bb804cd179ed17f2817adce1a2af94d` |
| 当前数据库清单 | `a7d2049dc3590ae2174d33bd3846b64c4b74045d07229ef92875fcce141e356d` |
| 当前配置清单 | `d2f815ca6e1f644a8319c74347a1bc3d7017d2ee4fa7fc7ae1ff9055ded95196` |
| 总清单 | `4217d227e7112a63f3a322195175b871c2ec7b7e677655087912de5c049b3868` |

原163清单保留为数据库目录的 `verified-manifest-at-163.json`，原SHA `8dd99df4cdc08be55ff5d56f69609ac4d6ef99102d798ae4150b627794b13a6d`；原174待交接清单保留为配置目录的 `verified-manifest-at-174.json`，原SHA `c7b29c4d2f79d084cbdb7467d4651769fc8001f2219ffbe28ed30a288264f38f`。173原封存中的memoryPending是封存当时状态，保持原字节，由168和总清单承接后续成功，未改写原证。

证据根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/`，本阶段主要目录为 `selective-context-download-173/`、`selective-context-recovery-check-168/`、`selective-backup-handoff-174/`。后者含 `parent-finalization-binding.json`、`finalization.stdout.json` 和 `final-ready-verification.json`；父最终以正常CIM确认整理进程退出、所有有效备份文件仍仅当前用户与SYSTEM可访问、原/当前清单SHA正确、staging为空，见 `parent-final-audit.json`。实际3299源码及JAR最终不变，见 `parent-final-source-verification.json`。本地 `test/data/` 和个人 `AGENTS.md` 由已有 `.git/info/exclude` 排除，备份和凭据不提交或推送。

## 收尾、用量与仍待验项

173正常CIM核10个自有PID+UTC CreationTicks全退；GUID凭据副本及UDS已清，原配置pair字节不变，原调查数据库删除0。168 launcher/Python两个自有进程全退，marker绑定的TMP已清；174回执复制和最终整理均同步结束。175公开probe的自有Runtime/TMP/UDS也已精确清理，父Gitapply的scratch已清。没有按目录前缀批量删除或终止无关进程。

173已知唯一APP响应input155054/output111/合155165，cached input768、miss154286；reasoning字段UNKNOWN，不重复计入output。两项必要能力probe、物理transport次数和外部完整账单UNKNOWN，没有额外付费补证。173之前的失败调试用量仍按原轮独立保留，不计入最终冻结八项目指标。

161/171及更早DENY/UNKNOWN、资源占用与调查证据保持；未完成下载目录151/156/161/171仅留调查元数据，不算有效备份。175公开假设样例双DENY仍为INCONCLUSIVE/probePass=false；Astra只对该额外诊断门与真实完整APP准入的区别作专项判断，未作为持续监督或强制终审。其原失败不因173成功改成通过。

源码3299项与175最终清单相同，冻结HEAD为 `2bc56abafb1fbe15b6e1f1ef3673169606066853`，JAR SHA `1e3f04a95201d5fdd131229093107130143636a48f67bfc8441125be8367af47`。本记录是后续文档交接，不改冻结源码。task-board完整独立审批业务、其余七项目、全新服务器冻结八项目、非默认目录真实部署和整机重启恢复仍待验；用户重装后重新核连接及主机身份再继续。
