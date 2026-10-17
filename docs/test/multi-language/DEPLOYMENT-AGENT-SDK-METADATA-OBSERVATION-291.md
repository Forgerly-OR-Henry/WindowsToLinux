# Agent SDK 元数据的单次原生审批观察 291

289在工具准备与fresh隔离审核中被拒，已由产品正式收尾并归档为FAILED；不再盲开FULL。现有入口不能同时提供安装记录字段、原生独立APP、新grant且不进入prepare/PROBE。本次只新增三个测试路径的定点观察入口，不改生产feature、批准器或operation注册，不改变STATIC/ASSISTED原生流程。

## 真实来源和副作用边界

唯一opt-in实机方法 `DeploymentStaticLiveAcceptanceTest#observesExistingSdkMetadataThroughNativeApproval` 固定当前已结清289 Runtime/原UUID，先查平台正式归档为PREFORMAL_RECONCILED/AGENT/FAILED，再用真实新TaskContext；不借旧Task、不伪造Agent journal/SOURCE。读取已存45.192.105.213:22/root配置并核实际hostkey，不调用enable/saveServer/configureLiveModel、不重写server/model/凭据、不打开七旧库。

公开observer.py自身经真实FileSelection和单文件SnapshotRequest冻结；执行正文来自该平台snapshot，原生AgentApproval绑定实际server/target fingerprint/public source snapshot，SourceBrowser可分页核同一正文。现有DeploymentCommandReviewScope和DeploymentModelCommandRunner负责exact request、独立APP/freshgrant、派发与独立receipt查询；观察性hook要求两个实际命令的grant ID不同，未确认的DISPATCHED不能releaseKnown。拒绝保完整脱敏理由，不重放。

SDK安装树只读，不是远端零写。既有envelope可能初始化/核对layout admission、创建删除systemd目录临时文件、写产品operation receipt；完整请求仍各自审核。不执行Go/Node SDK、64MiB PROBE、prepare/install/candidate，不修改SDK或共享helper。

## 元数据合同

固定观察 /usr/local/lib/windowstolinux/toolchains/versions；从根逐级fd-relative/no-follow打开，核root uid/gid、可写位、物理身份。最多32个根entry，GO/NODE slot和单链接普通marker严格命名/权限，marker<=64KiB、非阻塞/no-follow，字节数/身份/路径前后复核；两次完整扫描及parent链均须稳定，任何不可信或漂移为UNCONFIRMED。

拒重复JSON键及未知顶层字段；只输出版本、目录、origin和声明摘要等必要字段，排除source URL/system_files/dependencies。少量metadata摘要关系是协议校验，不hash SDK树或二进制。declaredBranchCompatible仅表示记录声明处于Go1.24/Node24分支；结果METADATA_ONLY，不证明真实版本、内容完整性或READY。根/marker缺失分别显式报告，输出<=60000B。

## 已完成定点

唯一本地方法 `DeploymentSdkMetadataObservationTest#boundedMetadataObserverRetainsAbsenceAndRejectsUntrustedOrDriftingEvidence`，显式Maven单方法筛选/package。真实读取同一observer资源，Python通过stdin执行synthetic case；Windows四个POSIX常量仅在本地mock ExitStack内补齐，远端脚本无弱化fallback。覆盖Go/Node声明、来源URL不输出、缺失marker、不可信字段/外来目录/权限及漂移；没有真实SDK树或Linux系统调用验证。

test01/session25856 actualexit0，1P/0F/0E/0Skip、1.652s，Maven18.802s/package0，UTC16:50:30.6535508Z—16:50:50.3609584Z。十二项直接source在package开始后0改变，JAR4253951B在该窗口。未重复旧通过组、未跑全量、无本轮provider/SSH。

辅助before、323行三路径窄diff与本地结果在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-sdk-metadata-observation-291/`。超最初250行控制目标的内容用于真实归档、来源、APP和fd稳定性，无生产抽象；提交保留迁移，只交付窄patch和限定摘要，不推送。CIM确认本轮Java0/terminated0，owner/边界/no-reparse后仅清c291a/u291t。三路径323增0删patch、reverse check通过；仅Git阶段六项限定摘要（三测试来源、JAR、patch、本说明），不提交迁移。

下一仅单次实机观察，需staticLive=true/sdkMetadataObservation=true、固定originalState/originalTask及既有fingerprint；独立审批可能拒绝，不能把本地测试当实机结果。仍无新FULL、无验收成功。旧七UNKNOWN保留，194/285/289已结清不恢复；File Transfer→CSV严格串行，两个完整BUSINESS v2/真实浏览器/STOPDISABLE后暂停最终审批/缓存指标。
