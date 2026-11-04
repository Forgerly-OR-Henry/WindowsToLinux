# 下一段对话交接提示词

复制以下正文到下一段对话。当前对话按用户要求结束并暂停未达成的目标；不得把暂停标记为目标完成。服务器计划由用户重装，恢复前应以用户最新私密信息和实际机器状态为准。

---

/goal [$gpt-6-x-agent-router](H:\_Save_\.codex\skills\gpt-6-x-agent-router\SKILL.md)

请接续 WindowsToLinux 项目的“AI 辅助部署连续首过验证”。先读取实际项目中的 `AGENTS.md`、`docs/test/multi-language/ASSISTED-CONTINUOUS-20261028-HANDOFF.md`、`docs/test/multi-language/ASSISTED-CONTINUOUS-20261028.md` 和 `.ai-workspace/progress/CURRENT.md`，再读取本文列出的当前候选整合记录和服务器回收回执。当前电脑项目主目录为 `H:\Program\WindowsToLinux`，若换机以实际完整工作区路径为准；先检查，不要根据旧路径盲目执行。

## 目标与验收边界

长期目标仍是：在清理并独立复查的一次性测试服务器上，同一冻结代码、同一冻结参数，八个既定项目各执行一次完整产品 AI 辅助部署。只有同一轮八项的原任务、既定完整业务、交付和清理全部首次通过，才算达成。不得拼接各轮成功、降低负载、恢复 UNKNOWN 后冒称首次通过，或把完整项目重试和人工补救当首过。内部有界纯生成恢复单独记录，不能称零异常。

此前“单项目连续三次通过再换项目”的方案已经撤回。仍一次验证八项；某项失败即记本轮失败，但继续同一冻结版本下其余安全可执行的原项目，了解全局影响。修复放隔离工作树，不能中途修改正在验收轮的代码或参数。后续修复必须比较历轮现象、原因及代码：区分新问题、老问题复发和分支尚未整合，不能反复把旧保护改回原样。失败轮保留原件，调查并修复已确认问题、完成相关定点后，再建立全新轮、清场及独立复查。

八项顺序：TaskBoard、File Transfer、Asset Lending、CSV Inspector、Survey Scoring、Log Analyzer、Directory Diff、Binary Inspector。沿用交接的既定验收入口及业务负载，不擅自扩大到全部 BUSINESS.md 的 10k/100k 压测或所有 CLI 场景。FTP 按用户授权使用 **8 MiB** 文件、**1 MiB** 分块，重启前 2 块、重启后 6 块；不要改回 64 MiB。正式预算为观察 token 上限 **1500000**、输出上限 **65536**、项目超时 **90 分钟**。

产品调用模型用 **gpt-6-luna / OPENAI_RESPONSES / OPENAI**；测试中主 App 的 LANGUAGE 和 APPROVAL 都设置 **HIGH**，Mod 不单设推理强度，继承主 App。`test/apikey.txt` 是本机忽略的私密配置，有 GPT 和 DeepSeek；不要自动切回付费 DeepSeek，也不要输出内容。用户已授权将必要的完整安装脚本、独立审批材料及测试证据经该 GPT 接口发送给指定模型，key 只用于认证，不进材料、日志和 Git。当前模型名称和推理参数应核对实际配置，不以本对话的主模型替代产品模型。

全程仅使用测试代码，不使用 Computer Use、真实 UI 或浏览器验收。真实部署必须用产品原有 AI 辅助流程、独立模型审批及真实宿主授权，不可用 SSH 手工部署替代。受控本地测试、只读远端维护回收、真实部署与完整业务验收分别记录。UNKNOWN 首因未确认就写未知；观测增强不等于底层故障修复。准备、编译、清理和辅助夹具失败单列，不删改八项分母。原失败和 UNKNOWN 状态库不擅自打开、恢复、重放或覆盖。

## 当前停止点和原始证据

R12 已中断收尾，**计划分母 8、原启动 5、完整首过 0**，不是八项都已执行失败：

| 项目 | 原记录 |
| --- | --- |
| TaskBoard | 原 root `31d20b21-80e4-4bc0-9a24-f66dec0d8706` FAILED，独立审批 REVIEW_DENIED，实际 native exit 1 |
| FTP | 原 root `9daffd37-61fb-48ee-8318-523066520787` UNKNOWN，retained uncertainty，无原 RootResult，实际 exit 1 |
| Asset Lending | 原 root `dd35a130-8d82-4128-acef-2fcaea906e02` UNKNOWN，retained uncertainty，无原 RootResult，实际 exit 1 |
| CSV Inspector | 原 root `88ae9ae4-d14d-4b39-a2c2-bbf78c6fc65a` UNKNOWN，retained uncertainty，无原 RootResult，实际 exit 1 |
| Survey Scoring | 原 root `de410835-f6ee-4d41-aedf-11b6f171d4d3` 最后记录 RUNNING，原 terminal、JUnit XML、native exit 均缺失，保留中断未完成，不能补判 FAILED/UNKNOWN |
| Log、Directory、Binary | 没有原 launch，未启动 |

旧 driver 工具句柄 62805、observer 54545 已不存在；宿主核对原 13 个进程身份均消失，禁止重启、重排或继续旧队列。最后采样为 `2026-11-02T06:56:10.028318Z`，其后覆盖缺口、driver/observer/Survey 退出码及中断原因未知。旧 PID 19472 被无关 unsecapp.exe 复用，不能据旧 PID 杀进程。原 340 公开文件及 9 状态目录 332 opaque 文件已封存并复核，健康正常凭据库排除；R11 的 317 个原件也核对未变。未对原 UNKNOWN 做恢复或远端 reconcile。

R12 证据 BASE：`H:\Program\WindowsToLinux\.ai-workspace\wt\r12f\.ai-workspace\evidence\round-12-preparation-01\12`。Nov4 中断审计、raw seal、进程与临时清理、最终冻结检查在主树 `.ai-workspace/evidence/assisted-resume-20261104-01/`。R12 源码 `caa4f99a22c1756ed574e49331988ee4881cec4d` 及原 SOURCE/P/R、控制文件、target 和失败状态均保留，不改写。旧轮摘要和统计以正式记录为准，不用早期“五轮”交接代替最新状态。

已确认 TaskBoard 在 installer dispatch 前被独立审查拒绝；没有证明远端软件源恶意或唯一拒绝原因。FTP/Asset/CSV 的 UNKNOWN 首因仍未查明，最后生成成功不等于后续审批、授权、执行成功，未证明三项同因或预算耗尽。R12 只读维护回收不能补造缺失原终态，也不能改变旧判定。

## 下一轮候选已经完成的本地工作

当前 MAIN 的前一收尾提交为 `42181ccd83fce23fc2071679cf97681e04750bc5`；本次新交接文档另有后续本地提交，以实际 `git log` 为准。生产整合候选在独立树 **`H:\Program\WindowsToLinux\.ai-workspace\wt\r13f`**，分支 `codex/assisted-integrated-observation-20261104`，已本地提交 **`1b646e8a4d25a7a86009b833244a24a4592cf920`**，未推送。该 merge 的父提交为 MAIN `42181ccd…` 和隔离链 `caa4f99a…`。MAIN 尚未合并这个候选，不要误用 MAIN 源码/target 当已整合产物。

请读候选中的 `docs/test/multi-language/ASSISTED-R13-INTEGRATION-20261104.md`。已整合 MAIN 镜像功能与旧隔离保护链，保留 0701 prior-byte/锁内准入、27f pregrant、env-i 固定 PATH/Bash-p、cff67 禁移除、aa804 READY/ACK/cgroup 生命周期、原 server/task 与单一期限；镜像进入同一 manifest/worker，未整文件覆盖另一分支。此前两边缺失来自未合并，不是镜像提交删除旧修复。

默认 bootstrap APT 新补 `--no-remove`，保留原安装 Python 能力；只证明禁止移除这一保护，不代表源、hook 和生命周期所有问题已解决，也不保证模型批准。测试 fixture 新增有界 typed first-observed 观测，保留原对象、抛出、句柄和调用次数，无新模型/SSH/inspect/重试。`authorDelegateExitObserved` 不等于平台 returned=true；内部 beforeSend/ledger 等尚未观测分支仍未知。

实际相关验证：Java **33 唯一方法/48 唯一 cases** 最终各有通过，实际 **51 次执行=48 pass+3 fail**，失败断言和 Files 初始化夹具问题已单列、仅重跑失败项；Python **60 唯一方法** 最终通过，实际 **104 次=102 pass+2 fail**，早期 stdout 未落盘的缺口在重建台账明示。最初 compile-only 缺 test-support classifier；正常定点 test 阶段及单独 Files 构建完成相关编译，没有改 POM 绕过。并非全量验证或真机验收。

Nov4 又完成必要 `-DskipTests package`，actual exit 0，未新增 JUnit：17 reactor、16 JAR（15 产品+1 test-support）、30 main/test 输出目录全成员 SHA、16 JAR CRC 和输出对应核验、三 Mod descriptor/marker 及相关资源核验。证据在候选 `.ai-workspace/evidence/r13-artifacts-20261104/`，重点读 `artifact-audit-summary.json`、`classpath-provenance.json`、`jar-inventory.json`、`output-directory-manifests.json`、`process-exit-verification.json`。显式 54 非空 CP=16 当前候选目录+38 公共依赖，无旧 R12 输出；原 Surefire raw 55 tokens 尾空项单记，实际隐含 CWD 的 classloader 影响未测，不得推断。development marker 不覆盖全部 Java 源码/字节码，对应关系以独立成员清单为准。

候选 Mod JAR SHA：deployment `51c0b117488b1591968e276ab720423dd4143e5228330746e86f3d0a85e55ca4`，files `96d4606b29763b4b47b125d66fd2ea38d5ff3fa9afed51024f85170e7abd15d2`，git `dff4f4f67654254e543b0b6032a88d65f005e53d488dcf8096e757eb31e784a0`。helper pin `535861d0ef1c0621d3eca7565895e3fe35267f422f834f174a604423d336f359`、build-entry pin `4fdd1d5d6661c31382647512e4efd96d6e5eb5ef1296512e2a3df25f49706e42`，七份真实 renderer 原件已复核。

**R13 尚未建立正式冻结、产品清场或八项部署。** 不重复无故运行已通过定点组。恢复时先核当前输入/实际产物是否仍匹配，再做必要的三 Mod 产品发现、provider/类路径与凭据/当前 API 检查，然后建立新的 SOURCE/P/R 和冻结控制。不能把旧 R12 control 的 source pin 改成新版本后冒称原冻结。

## 服务器与回收材料

用户拟在本次收尾后重装服务器。此前地址 `45.192.105.213:22`、账号 `root`，此前已授权指纹为 `SHA256:BiNl5kOr/2sc0vo657OtU0v6DjWAo/YUxZDUZRcjXlM`。这只是重装前已知身份，不能预授权重装后的新指纹。恢复前核对用户最新私密连接信息、实际身份和 SSH 指纹；同一已授权指纹无需重复确认，未经授权的新变化必须核实。不得在交接、源码、日志、Git 中写明文密码/API key。

重装前已完成两次独立只读回收：首份公开回执在主树 **`.ai-workspace/evidence/server-evidence-export-20261104/maintenance-export-receipt.json`**，49条 ZIP entries／199788字节；补充在 **`.ai-workspace/evidence/server-evidence-export-supplement-20261104/maintenance-export-receipt.json`**，恰8个已知绑定／归属小文件＋1manifest／4803字节。合计 **56份资料内容文件＋2份manifest**，两次均actual exit0、同已授权pin、truncated=false、ZIP CRC及DPAPI内存往返通过，每次各1连接检查／1真实授权／1远端export execute，新模型请求0。首份原19遗漏保留，补充遗漏0；这是维护资料，不是八正式项目重试，不改变R12结果。

原始内容仅在内存处理，私有密文分别为主树 **`src/app/db/data/maintenance-evidence-export-20261104-01/server-evidence.zip.dpapi`**（200006字节，SHA `6f1d5e862956dace2975fc499009dd5dcba2f8b1205b0e5353e3d41669bd0ef1`）与 **`src/app/db/data/maintenance-evidence-export-20261104-02/server-evidence.zip.dpapi`**（5030字节，SHA `658600ca797579290beeb04760640a3280e1bf1757b34b55aee76a892f0c6074`），不提交Git。raw ZIP SHA分别为 `6cd97260520937a406cb90cf2121123068a8d62114d2f6136810d49a4a2ea5fe`、`2caffc09b17b0285823f5f9b6dba413b1b04b0563d6148ab3e61f175123a39ba`。公开回执含条目、范围和裁剪/遗漏：包版本、APT/dpkg配置历史、toolchain诊断/request、helper receipt、unit状态及小型绑定/归属元数据已保留；一份dpkg历史只保留尾部，3root不存在，大SDK/缓存/4GiB工作镜像/秘密路径等排除，不是全机备份。综合收尾见首份目录最终记录。

不要为分析方便转储明文或重开旧 FAILED/UNKNOWN 状态。归档受当前Windows用户DPAPI保护，换机不能假定复制即可解密，应在原身份下用另行授权的安全方式迁移或重新录入；同机分析也须最小范围内存读取、避免任何秘密进入日志或Git。服务器重装会丢失未纳入回收范围的内容，这一点不可描述为全量恢复保障。完整回收和辅助问题说明见首份目录 `CLOSEOUT.md`、`combined-closeout.json`；两个维护tmp已清，父独立最终审计核13原身份均退、当前任务匹配0，两份实际密文SHA一致。首次sandbox私有元数据读拒绝、提升身份清hsperfdata拒绝及后续合规处理均单列，不混入八项分母。

测试机所有必要操作（包括系统/内核级清场）已授权，本地项目必要修改已授权；本机内核/系统设置修改须先询问。不要擅自重装用户服务器；由用户重装后提供最新信息。服务器即便已重装，也须按产品 inventory→plan→apply→独立 after 验证新轮干净，才开始同一冻结八原项目。不得因测试机将重装而删除本地原始证据或抹掉 UNKNOWN。

## 工具、工作区和后续执行

本机曾验证工具：JDK `E:\Program\Java\JDK21`；Maven `E:\Program\Java\maven\apache-maven-3.9.12\bin\mvn.cmd`；Python `E:\Program\Python\Python3.12\python.exe`；Git `E:\Program\Git\cmd\git.exe`。Maven 用户/全局 settings 均显式指向主树 `.ai-workspace/evidence/assisted-continuous-20261028/resume-preflight-01/maven-central-settings.xml`；公共仓库 `E:\Program\Java\maven\repository`。换机先核实际路径、依赖和编译来源，不能只复制文档或从远端重新 clone。

保留完整工作区、`.git`、linked worktree 及元数据、全部原证据、隔离状态库、私密配置和旧工具。R12 健康 normal vault 为 `src/app/db/data/agent-live-credential-20261102-r12-01`（相对 r12f），只可按产品 Security 的 char[] 安全流程使用，finally 清零，不复制/覆盖旧凭据库或解密转储；重装后旧密码不能假定仍有效。最新 API-only 成功证据在 Nov2，不等于 Nov4 或新机当前连通性。新状态必须使用新路径，旧原件留存。

只跑直接相关定点及必要编译，测试命令显式限定范围。代码及配套变更验证后本地 Git 提交，默认不推送。MAIN 用户论文有 3 个 tracked 删除、1 个 DOCX 修改及 1 个未跟踪 DOCX，属于用户任务，全部保留，不自动暂存。AGENTS.md 永不提交；用本地 exclude。辅助临时目录统一项目 `.ai-workspace/tmp/<任务>/`，原始证据在 `.ai-workspace/evidence/<任务>/`；子进程显式 cwd、TEMP/TMP 与适用 Java tmp，CMD 可执行路径用原生反斜杠，目录创建与启动分开。每轮和交付前核退本任务自有进程并清精确临时项，不能碰无关服务、代理、IDE 或复用 PID。

继续启用本机最新版 router skill；一般子代理 Sol High，耦合约束工作可 Sol XHigh，Astra 只处理具体困难问题，仅 high/xhigh，不自动每步复审。禁止 Computer Use 的用户约束优先。本阶段曾用 Astra 解决 bootstrap 边界及 UNKNOWN 观测设计两个具体问题，没有 Terra/CU。

请先确认完整资料、候选提交与服务器重装/最新凭据状态，继续已授权且不依赖缺失答案的工作；只有确需用户的信息才提最少问题。新轮前重新核对冻结、真实清场及独立复查。不要恢复旧队列，不把本地定点或维护回收写成正式首过；逐轮保留失败、已确认/未确认原因、解决方案、验证范围及实际结果。
