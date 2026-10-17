# Agent 原238合同诊断后本地账本观察（273）

本轮仅对原Runtime做一次existing-only本地只读核账，不运行产品恢复、模型或SSH，不构成业务验收或结清。前置实现与产品协调分别见[271](DEPLOYMENT-AGENT-PUBLICATION-CONTRACT-DIAGNOSTIC-271.md)、[272](DEPLOYMENT-AGENT-NATIVE-CONTRACT-DIAGNOSTIC-272.md)。

原Task为`072bc074-4bce-4c14-b425-f2ff965c7d40`，Runtime为`src/app/db/data/agent-live-file-transfer-8pqtfa76o`，App为`wtl-agent-file-transfer-8pqtgvbwc`，execution为`45e3a9a4-a6b8-403a-89f5-605e11eeeca0`。证据根为`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-post-contract-diagnostic-ledger-273/`。

## 实际执行与冻结

- byte capture、隔离javac及唯一reader均实际exit0；reader stderr为0B。reader只用既有bootstrap、原空锁及H2只读连接，没有Runtime/伪TaskContext、凭据行或网络访问。
- baseline SHA为`d5bc72dc8134e7b21cb0c6cab3900935158df13439a16c0770a57429ef1af377`；source3337，manifest为`23b159357f334d3f91b0fd6e7050092bacc2d4729b3ffee155c3605cc94f4eee`，六JAR及54CP/3294成员保持冻结。
- 原DB为226136064B/SHA`a89d082073aac102fed0b026d220c9060fed993f21a15725430f0a2c04c4d120`；bootstrap为358B/SHA`81c546baf9dc3e7632155d86c8ae6d657109f82f1608e01006ca97ec07f7bd8b`。锁内与关闭后pair均未变，原空锁身份保持。
- `stdout.json`为159162B/SHA`526250578351fae1b14e870f27a75f8aefe3162da9e9331e6186a8f13b68968a`；public requireInitial、完整Compensation前缀、typed binding、请求重建及新Program回执codec均已实际通过。

## 新诊断原账

| 事件 | 已证事实 |
| --- | --- |
| 449 REQUEST | 新合同诊断，绑定原Task、旧pending、385–391及417–426整图 |
| 450 REVIEW | 独立APP APPROVE，confirmation=false，rememberAuthorization=false |
| 451 GRANT / 452 DISPATCH | 新grant及真实发送记录已保存 |
| 453 RESULT | observedOutcome=SUCCEEDED，fixedReceiptVerified=true，parts=2 |
| 454–455 / 456 END | 两安全回执分片完整，END已核 |

action为`dce2e4ed-de76-4c01-894b-99ee1b980cb7`；receipt SHA为`c1d498f541cb37c9008224ef8ad8b9b1d644bea270b1600617ea5dfe3a1b5b50`。安全回执为`UNKNOWN / CONTROL / UNCONFIRMED`，exitCode=null；三个checks均为`UNKNOWN / NOT_CHECKED / null`。

上述结果只说明未取得可接受的控制序列，不能证明三个真实函数未进入或未执行，也不能推定backend新状态。transport SUCCEEDED不等于合同通过、goal、回滚ACK或结清；CONTROL原因留待独立诊断，不在本记录猜测。

原事件272的FAILED/UNKNOWN、385–391接续回执及安装READY266保留，未补ACK、未重发旧动作或重装。当前completed=0、pending=true、verified=false、lastActionEvent=456；最近完整wholegraph仍为417–426，早于456，不能用于此次动作后的结清。

平台和Mod仍UNKNOWN，终态outcome/endedAt为空；275条原事件，public effectsSettled=false。资源15058条，其中15053 released、5 retained；限定明细7条。execution仍在，正式App/component/release及operationReceipt均0；本地0行不证明远端无效果。

## 准备失败、修正与收尾

首capture以`KeyError: programIdentityProof266`退出1，发生在输出文件、Java和DB打开之前，原失败保存在`parent-proof-correction273/failure273.json`。父仅修为实际`programIdentityProof271 → anchor266`链并保旧锚校验，随后capture成功；未用失败记录冒充通过。

两处旧2temp守门改为真实TMP、UDS和`tools/empty-sourcepath272`三路径精确匹配。编译/read外壳补启动后立即排空输出、异常finally等待/收集/Dispose；身份未观测只UNKNOWN，Java退出未证不做后置byte核对。准备preimages、差异和静态失败/修正证据均保留。

normal finalizer核4个精确PID/time退出；独立audit核含finalizer在内5pairs全退。自有空sourcepath删除，capture/reader/close三binding执行位全false；未终止无关进程。此前272的主记录29pairs及独立audit30与closer仅actualexit0、无自身pair的限制仍保留，不扩写为所有工具均有退出身份。

本轮没有新FULL或完整BUSINESS通过；历史9FULL（File Transfer8次、CSV历史208一轮1次）及0完整BUSINESS不变。继续目标为File Transfer完整BUSINESSv2、真实浏览器与必要正式收尾，再串行CSV；两项目完成后暂停，分项目报告FULL审批I/O占比、cache及已知/未知，失败与纯恢复分列，等待真人方向。不恢复194/234，221不自动恢复，不自动进入第三项目。

本阶段无production修改或新patch；本地提交与273封存由父后置完成，本文不预填其标识。
