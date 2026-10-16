# 原194单任务产品恢复验收225

2026-10-16，本轮真实产品恢复为 **1 FAIL、0 ERROR、0 SKIP**。原任务仍为 `UNKNOWN`，未证明远端候选或容量已结清。完整部署目标尚未完成；本轮不计为新部署，也不增加所选两个项目的7次 FULL 数量。

## 范围与基线

- 仅恢复 Task `a67e3b89-d04e-49a7-8b5e-20ed93803d53`，原 Runtime 为 `src/app/db/data/agent-live-task-board-q3k1ohhos`，App 身份为 `wtl-agent-task-board-q3k1qalz8`。
- 基线 HEAD 为 `f4f6effb6bad2a99240ec1ada61f7baee0ca58c5`，index 为空。3310份实际源码与224最终清单一致，原224封存387文件逐SHA复核相同。保留混合工作树和其它任务改动，没有切换干净工作树或整体暂存源码。
- SSH 目标仍为 `45.192.105.213:22/root`，沿用用户确认的重装后指纹 `SHA256:sCRc3ICCT9OMtsaGxjciDIQd0NMVO5/+pDe/fNMgRYc`。本次输出不足以证明新的连接或容量结果，不能把失败归为服务器连接失败。
- `192.168.201.1` 经本机 .NET 网卡查询仍属于 VMware VMnet1。212以此覆盖本地非 localhost HTTP 访问，不代表在虚拟机或远端 Linux 完成业务部署。

当前构件 SHA-256：

| 构件 | SHA-256 |
| --- | --- |
| deployment.jar | `34ecae6786405da2912bb92695b0377aa4a2a172ff06110ba7e878e75b640325` |
| host AI JAR | `74ff9dfb37104c23e10baa409a18c2723fc1ab33848e5550af6a6e3ac5429863` |
| appapi.jar | `28f491b7172e1096969f02474013b0e736c6640ed732c0d14df030d93874d1d8` |

## 必要备份与保存配置

恢复前仅将原数据库59060224字节和引导文件358字节备份至 `test/data/original194-before-product-recovery-225-1510608beafb4a2bac0f91cfa2c9d2f8`，另存备份清单；没有导出整个 Runtime 或远端23 GB目录。该目录已由本地 `.git/info/exclude` 排除。

备份持有原产品排他空锁，并以 `Read/FileShare.Read` 句柄拒绝原文件写入和删除。新目录权限仅当前用户与 SYSTEM；父独立复核规范路径、无重解析点、权限及原件/备份 SHA。数据库原SHA为 `11d49c71edf4071bb06a1cebd85969f562f908aa4a82d8848165adaa91ca7b4a`，引导为 `886b792519a8fe8ed0dc5ac894eef95c3ca1856617f0448ccafe3bba4b133f8e`。不宣称整个 H2 文件加密；引导的产品保护保持原样。禁止在远端动作发生后自动还原旧数据库。

随后只对私有备份执行一次裸 H2 元数据查询：`IFEXISTS/ACCESS_MODE_DATA=r`、schema1和原generation均通过，exit0、stderr空，读取后pair SHA不变。正常用户 DPAPI 仅在内存解开数据库引导；没有初始化 Runtime、读取 credential 行、SSH/API秘密或联网。

两个保存的角色均为 `https://api.deepseek.com/chat/completions`、`deepseek-flash`，部署 LOW、审批 NONE，符合本会话既有数据出站授权。已有 TEXT/TOOL 能力证据绑定当前修订，仅复用保存记录，不当作新网络探测。界面保存的旧output值均32768；产品请求通过 `DeploymentAiJson` 按purpose采用配置普通32768、审批8192，本轮两个实际审批请求的effectiveMaxOutput均8192。未修改配置或重新探测模型。

## 实际执行与失败

工具交付有2份PS AST零错误、3份Python AST及7项新增纯守门检查。父另实际执行离线capture，核3310源、29部署构件组、3宿主类族、当前JAR及54个有效classpath入口/3202文件；原末空项被排除，没有当作工作区。offline SHA为 `d65db8114cda6f5f9f2a1642f7fbe189f822a5d898557404d9a9471bc993746a`。这是静态字节绑定，不是JVM CodeSource。

唯一执行为 `DeploymentStaticLiveAcceptanceTest#reconcilesOriginalTaskThroughProduct`，通过既有 Maven `-pl src/mod/deployment -am -Dtest=... test` 精确筛选。设置 `originalTaskOnly=true`、`originalPreparationOnly=false`、`configureAgentRecoveryModel=false`，沿用原30分钟等待，没有打包、新capability probe、其它旧任务遍历、新部署或自动重试。所有当前远端动作仍须独立审批及新grant，未改审批政策或代替模型操作服务器。

exec58458已终态exit1，before/after-source均0。产品真实进入 `RECOVERY/MOD_RECOVERY_READY`，方法运行32.017秒后返回：

- 原Task `UNKNOWN`、outcome为空、progress为 `AI_RECOVERY_ORIGINAL_EVIDENCE`。
- 两个 FILE source-snapshot blocker及业务结果reconciliation blocker仍在。
- 唯一JUnit断言在5834行要求 `ENDED`，实际为 `UNKNOWN`；关闭时另保留“原Runtime仍持有资源”的suppressed异常。

该输出没有具体审批裁决、新grant或SSH回执。`SUCCEEDED` 的模型usage仅表示生成成功，不能解释为 `APPROVE`；也不能仅从进度标签认定审批拒绝、网络失败或候选校验失败。本次reconcile正常返回后才触发断言，未达到30分钟等待超时；这一事实不能排除内部短请求失败。

直接源码核查确认 finally 先取最后100条诊断，再仅打印usage，其它固定错误/观察记录被过滤。因此目前确认的是公开日志存在观察缺口，具体拒因仍未知，本轮没有据此修改产品。下一步需验证原schema后，只读本Task、本轮时间内的持久诊断，投影安全错误类别/validation字段；另核精确multi checkpoint、新增event_id>97及原97前缀摘要，不再遍历3454资源。保留未知和全部原件，定位实际拒因后再决定最小修复，不重放原动作或付费碰运气。

## 本轮用量与收尾

原Task最后100条诊断为 `OBSERVED/truncated=false`，其中86条为usage。按recordedAt和startedAt双时间界限，排除84条旧194记录，本轮已知2条APPROVAL记录、2个不同attempt ID：

| 项目 | 本轮已知值 |
| --- | ---: |
| Input / Output | 17547 / 982 |
| 合计Token | 18529 |
| Cache hit / miss | 13056 / 4491 |
| 已知输入缓存命中率 | 74.4058813% |
| 已知Token中的审批占比 | 100% |

这是纯审批恢复窗口，不能同 FULL 部署的审批占比直接比较，也不是最终八项目指标。两条reasoning均未知，不另加到output；完整generation数量、physical HTTP数量和整单费用仍未知，不补付费取证。

恢复后数据库大小59060224字节、SHA为 `92e170a2fec8718a3077be0f84c957db98af3c652c77961d55b701204d478a7c`，引导SHA保持原值。父在原产品锁下复核与after-metadata一致；未还原或删除原数据。before/after源码、构件及classpath全同。

正常用户Close已核15个本轮精确PID+创建时间全部退出，父另独核通过，自有TMP及短UDS不存在。元数据读取的launcher、Java和closer另3组也均退出。原UNKNOWN、资源、调查数据库和必要备份保留。没有重试197/201或旧58/74/116。

证据位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/original194-product-recovery-225/`。入口为 `parent-backup-verification225.json`、`parent-model-verification225.json`、`parent-product-release225.json`、`parent-terminal-verification225.json`、`parent-product-closure225.json`、`provider-usage225.json` 及唯一run中的原XML/日志。独立只读 `terminal-audit225.md` SHA为 `950c72bd9ca954eea43664a885935257e34794d8ef91cac91fc707d4d67bf5d2`，列出上述缺口及下一次最小字段。本轮没有修改产品源码或重新打包。未使用GPT-6-Astra。本轮仍未证明业务可运行、远端容量回收、冻结八项目、非默认目录或整机重启恢复。
