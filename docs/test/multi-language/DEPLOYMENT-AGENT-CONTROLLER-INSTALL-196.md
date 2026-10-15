# 196：Agent固定控制器版本安装与完整短调用

控制器版本安装、逐次短调用、持久归属及相关审批／恢复接线已完成本地验证。**最终有效37个定点case通过，非全量；唯一skipTests打包通过。** 本轮没有provider、SSH、DPAPI或真实部署操作，不能宣称审批占比、缓存命中率或费用已经改善。

本阶段承接[195第一阶段优化](DEPLOYMENT-AGENT-EVIDENCE-EFFICIENCY-195.md)和已核实的人类第二阶段授权：先固定controller版本安装与短调用，完成本地实现和定点交付，不因测量自动追加付费部署。独立审批完整动作、修正后重新授权的要求保持。[186完整task-board验收](DEPLOYMENT-FRESH-FULL-ACCEPTANCE-186.md)是旧版本历史PASS；[194失败窗口](DEPLOYMENT-AGENT-EFFICIENCY-LIVE-194.md)的UNKNOWN和资源证据保持原样，均不能当作196真实验收。

## 实际输入如何缩小

原prepare／bind每次通过stdin带入约100 KiB的完整controller源码。现在仅将这两个Agent入口切换为受保护版本目录下的固定程序；resolver和dependencies保持原inline算法，不顺带改动STATIC／ASSISTED编排。

版本目录为任务捕获的 `layout.toolchains()/agent-controller-programs/<version>`，没有current／latest。独立literal manifest先校验随包完整controller、loader、installer与review contract的摘要，再按manifest、captured layout和完整controller源码摘要派生版本。version与sourceSha256分别记录，不能以版本名冒充源文件摘要。原controller资源未改；默认渲染完整源码仍为101,904字节，SHA256 `2f82c5c50834f747ff005832edafbc47e9bed72103a98a538a8242d8ed4f3c19`。

首次使用先独立审批并执行readiness；只有严格MISSING才能进入安装。首装／新版本安装独立审批完整installer及完整嵌入controller原文，包含目录、权限、版本与副作用，并取得新grant。现有READY必须带完整可信安装回执；复用时保留原installTask／installOperation，不改成当前调用者。部分文件、同版残stage、锁争用、坏回执或无法证明的状态不能冒充MISSING，不自动重装。

后续每次prepare／bind仍独立审阅完整短loader、全部当前typed argv、decoded binding、目标资源、版本、sourceSha256、原安装回执和当前durable action；精确SSH timeout、outputLimit、stdin长度及摘要进入证据。stdin为空。每动作单独取得绑定当前请求的新grant；新installed语义与旧inline完整正文匹配器分开，不借旧hash标签豁免完整当前输入。

| 本地受控bind示例 | 实际字节／次数 |
|---|---:|
| 原script＋stdin UTF8 | 102,622 B |
| 新完整script＋空stdin UTF8 | 7,816 B |
| 新script UTF16码元 | 7,816 |
| 初始inline JSON UTF8 | 8,411 B |
| 首装完整installer stdin UTF8 | 116,247 B |
| 该mock完整裁决生成 | 1次，随后取得新grant |

调用载荷示例约减少92.4%，仍在原8,000码元／14,000编码字节边界内，没有扩大窗口或输出预算。仅将loader中等值Python字符串换用双引号，避免Bash单引号扩展336码元；Python AST相同，未压缩代码或删守门。**首次安装增加完整安装输入与readiness成本；部署总成本取决于后续调用次数和模型自主行为，不能把载荷字节降幅当作token或审批占比降幅。** 193／195的缓存前缀、批量取证和Skill去重继续保留。

## 安装、执行与未结资源边界

loader逐段打开目录，检查root归属和禁止组／其它用户写；文件要求regular、root、0444、单link及有界大小。通过同一个已打开FD读取和校验前后identity、完整SHA，再只编译执行这些已核字节，保留原PROGRAM_SOURCE。特殊对象以NONBLOCK打开后fstat，避免FIFO在校验前卡住；readiness不执行controller。共享锁覆盖整个观察过程，安装使用独占非阻塞锁。

installer仅新建明确声明的toolchains／storeRoot；上游受保护祖先须存在，不任意补建整条目录链。私有stage验证完成后通过 `renameat2(RENAME_NOREPLACE)` 发布，不覆盖已存在版本，不在不支持时回退为覆盖rename。路径逐段检查是必要条件：[open(2)](https://man7.org/linux/man-pages/man2/open.2.html)的O_NOFOLLOW仅约束末段；[flock(2)](https://man7.org/linux/man-pages/man2/flock.2.html)依赖协作锁；[rename(2)](https://man7.org/linux/man-pages/man2/rename.2.html)的NOREPLACE依赖实际内核／文件系统支持。文档审阅不能代替真实Linux验证。

原TaskCheckpoint与ActionLog保存task、server、captured layout digest、version／path／sourceSha256、operation、request SHA与原回执。INIT先于审批，实际结果和fingerprint持久后才允许释放相应占用；保留payload和有界摘要。安装成功不等于prepare／bind成功，后者继续经过原资产严格回执门。另一个task的READY不能结清原task UNKNOWN。Recovery在formal／preformal分支前及Cleanup共同检查未确认controller动作，不能以旧PREPARE摘要跳过。

原Channel新增窄per-send observer overload，旧send委托无副作用observer。原grant及beforeSend守门通过后，在execute紧前记录本次活调用边界。只有当前调用已经结束、且确实未到边界，才记录NOT_SENT、保留拒因并释放本次占用，让模型自行修改后重新审批；已到发送边界的失败／UNKNOWN继续保留，不自动重放，不从历史日志或NOT_STARTED标签追补证明。Fixed注册表只增加四个精确controller操作，原注册／静态／请求一致性／独立审批／单次grant守门不放宽。

旧新版本、失败stage、FAILED／UNKNOWN和关联调查继续保留；196没有GC、refcount删除或磁盘回收实现。每op controller副本、manifest、动态非root worker、隔离卷及原stop／freeze／detach数据提升流程保持。非默认layout仅窄修Release.decode按实际captured layout校验resolver SHA，原无参默认入口仍保留。

## 定点验证与原失败

显式方法selector位于 `implementation/planned-selectors.json`；仅运行本阶段直接方法和失败后的直接受影响方法，不执行全类、模块或全项目测试。覆盖manifest／模板钉住、首装／同版复用／跨task／版本并存、篡改与部分回执、原安装归属、参数与targets／layout绑定、独立裁决与新grant、安装取消／失败／UNKNOWN、未发送与发送后未知、durable index／journal、cleanup／recovery门禁。

| 轮次 | 实际执行 | 实际结果 |
|---|---:|---|
| test-01 | 0 | testCompile失败，无case执行，原日志保留 |
| test-02 | 35 | 27 PASS／4 FAIL／4 ERROR |
| test-03 | 8 | 只重原失败case，7 PASS／1 FAIL |
| test-04 | 2 | 剩余失败方法＋必要安装终态／跨task方法，2 PASS |
| test-05 | 5 | quote／单页边界直接方法，5 PASS |
| test-06 | 3 | sourceSha256持久字段相关State两方法＋installed selector，3 PASS |

testCompile失败仅修夹具final初始化和缺失import。test-02发现真实Fixed操作注册缺项，已精确补四项；其余失败涉及UNKNOWN假回执缺必需Problem、cleanup拒绝在构造期抛出、193后旧native夹具仍取lastUSER及非默认layout夹具仍写默认resolver SHA，逐项按实际原因修正，没有生产fallback或放宽静态规则。test-03剩余旧inline layout夹具改用resolver(layout)。已通过方法直接复用，后续只跑失败及直接影响范围。

**总实际53次：44 PASS、原5 FAIL和4 ERROR，0 SKIP；替换失败重跑后最终有效37 PASS。** 原失败报告不删除、不改写。format-01..06仅声明Java owner的write／check全部exit0，Python AST检查通过，实际Java渲染pin通过。Linux FD／权限／锁／竞争／发布采用实际shipped Python函数配内存os／fcntl／rename stub；Java采用NativeAiFixture、fake grant、内存checkpoint与自有临时H2。正常用户已核本机无WSL、无可用docker／podman，本轮没有native Linux内核验证或远端验收。

## 独核、打包、收尾与提交

源码清单3299→3307，仅27个声明owner变化：19既存＋8新增，其中Programs与DirectoryToolchainTest两文件仅格式、词法token相同。补丁相对本轮实际dirty working bytes，不是相对干净HEAD。父独核105份原件、当前3307源码、真实XML／selector及有效结果，在自有scratch执行Git apply正逆check和实际应用，逐字验证19原文件与8新增文件；scratch已清。normal CIM已观测123组验证PID／creation ticks全退，TMP／短UDS清，末次confirmation进程另核退出。

随后唯一执行 `-B -pl src/mod/deployment -am -DskipTests package`，Maven exit0、16.767秒，前后源码守门exit0、3307项同字节，14处skip、0测试执行。最终 `deployment.jar` SHA256 `36d4d9e72b10065c4f5b4942c7449fb70560b7f396d8c79033cb9d82ec2286e7`。17组生产owner的全部类成员／资源与source、target、JAR实际字节匹配；SourceBrowser、InputCoverage、NativeHistory、审批policy、宿主AI JAR／两个HTTP类及真实验收fixture保持打包前字节。

打包另外12组normal PID／ticks全部退出，自有TMP／UDS清，原数据删除0。收尾helper首次在任何删除前因stage字面量中斜杠与Resolve-Path反斜杠不等而拒绝；保留原helper／错误记录，只新建单字节路径修正版完成原收尾，未重包／重测。修正版进程已退出。

本地提交仅包含本记录、[本轮精确源码增量补丁](patches/AGENT-CONTROLLER-VERSIONED-196.patch)和该单路径 `-text` 属性。迁移及其它任务的混合源码修改继续保留，不整体暂存，不声称干净HEAD能构建当前产品，不推送。

原件根 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/controller-versioned-invocation-196/`：

- `implementation/after-manifest.json`：`0f0a3a404fc1b0ecacf00f34762a2197e40a4cd6a753b021fa6a15d4b9a8811d`；精确patch `d49bf1de0fa0ba9939c9bb79a07d6467f3205c8c832c76b003ad3edf0036c29d`。
- 实现seal：`c62b2b09d2ba6ab8aabc4a0503431752ab8c805b2e296245294d331a7a98c86d`；父 `parent-verification196.json`：`17ff8257827d13308552484560260bfccbf25fa684280cd26b0d3f8054a968db`。
- `parent-package-verification196.json`：`8898d030887574513374793964606a611491385d9d446116a2174ceae4cdb873`，独核52份打包原件及实际构件／skip／normal收尾，原失败与路径修复原件均保留。

Sol XHigh完成实现与直接验证，Sol High准备包控制工具；Astra只参与固定loader／独立contract／留存生命周期三个具体设计判断，没有替代实际验证。整个原目标仍未完成：196真实Linux／产品业务及provider收益、其余七项目、冻结后全新机八项目、非默认目录真实部署、整机重启恢复和194原任务资源正式结清仍待。
