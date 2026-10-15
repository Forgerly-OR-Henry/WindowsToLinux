# 193：稳定独立审批的证据前缀，并有界预置短输入

本轮完成本地实现、定点验证及显式跳过测试的打包。真实模型的审批占比和缓存收益尚未验证；186 的 task-board 完整业务通过证据仍有效，其已知审批占比 **82.9103%**、输入加权缓存命中率 **68.6386%** 不能当作优化后的结果。暂不推进其余七项目，下一轮仅通过 task-board 产品入口测量。

## 依据与改动

186 已知447条唯一平台 generation attempt，包括主部署30条、独立审批417条。主部署 input 1,360,885、output 37,520；审批 input 6,637,597、output 146,735；合计8,182,737 token。审批输入占其自身97.84%，仅降低输出上限不能解决主要消耗。合计 input 7,998,482、cached input 5,490,048；缓存已包含在 input 中，不能再次相加。主部署 reasoning 25,498 已包含在 output；审批417条 reasoning 未知，不能填0。四个必要能力探测的用量、完整物理HTTP请求数及账单仍未知，不付费补证。

187固定截面的77个分析区间首轮均读取 request/script，其中75个只读取 request/evidence；这些是日志分析区间，不冒充产品 reviewBinding。全部首轮合计644,961 token，仅占审批9.51%；即假设全部消除、其余不变，审批占比仍约81.45%。短输入预置本身不足以保证降至20%。同一101,904字节正文被两条路径在同次审批完整重复读取的假设已被原记录否定，不据此添加别名体系。

另核实际历史组装发现：旧首轮完整USER在后续请求变成历史引用；自主激活Skill又改变首SYSTEM，可能破坏已有消息前缀。原SYSTEM摘要仅覆盖领先SYSTEM文本，不等于完整消息、工具定义或供应商缓存键。尾部100条诊断中98条属于审批；15次同分析区间SYSTEM摘要变化均紧接Skill，但这是相关证据，不能视为缓存因果实验。已有Map排序和稳定工具集合没有显示随机抖动，不重复增加排序、填充或预热。

只修改三个生产类、一份所属规则资源及其三个直接测试：

- `DeploymentNativeHistory` 新增四参数 `agentMessages`，仅供独立Agent审批选择。顺序为基础SYSTEM、一份固定当前证据USER、完整历史批次、模型自主激活的可信Skill SYSTEM、动态USER。Skill不再重写此前基础SYSTEM；首份证据持续保留。
- `DeploymentAgentApproval` 每个具体动作分别建立history和coverage，固定输入只序列化一次；剩余次数、reviewMemory等继续在动态尾部更新。只有模型调用成功、原绑定核对通过，且实际发送消息中的固定USER逐字存在，才记为输入已送达。失败、取消或未发送不产生coverage或grant。
- `DeploymentAgentInputCoverage` 在原单页尺度内预置完整短script/stdin：合计最多8,000个UTF16码元，实际inline JSON最多14,000个UTF8字节。依次选择能完整容纳的正文；不合预算保留完整descriptor，仍由模型自主分页，不截断或预取长正文。OPAQUE不解码，structured重建及叶节点覆盖规则不变。

固定政策、证据、已激活Skill、动态尾部及原native参数/结果/continuation全部计入既有字符和字节预算。完整批次裁剪、可见tool result绑定、原公共1MiB最终编码上限保留；不扩大窗口。原两参数调用及共享history接口保持原行为，STATIC、ASSISTED、主部署编排、插件reviewSource分支、配置、输出限额与超时未改。固定证据是USER数据，不提供授权；完整输入、独立裁决、CLOSED DENY、精确新grant和静态条件均保留。

泛型facts仍按原512尺度呈现，不声称任意相同文本均被去重；固定 `executableInput` 只预置一次，模型自主复读产生的工具正文仍是真实新观察。此次没有把所有Skill预载进请求。

## 实际定点验证

`implementation/planned-selectors.json` 显式限定三个测试类内25个具体方法，其中5个新增；不是全类、模块或全项目测试。新增覆盖稳定证据与自主Skill的实际消息顺序、Unicode/continuation预算、短输入原文与JSON双预算、失败不能制造送达、动态notes与自主复读不能产生grant。其余仅选择直接受影响的完整输入、185工具链绑定、OPAQUE、旧两参数history与整批裁剪等方法。

首轮执行30次用例：28 PASS、2 FAIL、0 ERROR、0 SKIP，Maven exit1/source freeze0。两处失败均为测试前提：新mock硬编码旧remaining=80，而实际为256；旧96KiB受控正文在新布局中仍未被裁掉。分别改用首个实际remaining验证消耗，以及160KiB公开受控正文确保确实裁剪，没有为断言改生产预算。末轮仅重跑这两个方法，2 PASS、Maven exit0/source freeze0；其余28次通过复用，原失败日志/XML保留。

最后仅澄清单位名称：新inline字节常量改为 `INLINE_PAYLOAD_BYTES`，数值仍14,000；原tool-result `text.length() > 14000` 字符守门保持原值与算法。未再次运行30次用例；父独核三处owner仅发生约定名称转换，随后唯一打包编译确认最终引用。六个Java owner定点格式检查通过，资源规则人工差分检查通过。

受控实际编码验证：稳定前缀为2个AiMessage、内容合计56 UTF8字节；完整两结果及已激活Skill的最终wire为940字节。缺失或乱序结果继续拒绝。ASCII 7,000码元正文完整预置后inline JSON 7,478字节；多字节、控制转义及emoji向量超任一预算均返回descriptor，不截断。APP mock固定证据4,206字节、动态尾部866字节，notes/remaining改变不改固定证据；模型仍自主复读一次。这些是公开受控字节事实，不能换算成供应商token、命中率或费用收益。

另对父审提出的UTF16页边疑点做纯本地实际Java观察：半个emoji页在旧公共codec同样不可编码，新模式只是更早拒绝；完整emoji页通过新模式与公共codec。没有扩大生产Unicode修复范围，也不声称所有半surrogate分页原本可发送。正常用户CIM核43组已观测自有PID/creation UTC ticks均退出，精确TMP/短UDS已清。

## 打包、隔离及后续边界

唯一执行 `-B -pl src/mod/deployment -am -DskipTests package`，Maven exit0、16.284秒，14处跳过测试、0次测试执行。最终 `deployment.jar` SHA256为 `d7c8c5cfa4b17779814878cfdd31c242be4aade783f80c1cc99633486c1a5de3`。JAR内三个修改类与target逐字相同，规则资源与source相同；宿主AI JAR、两HTTP类及验收fixture未变。正常CIM核另12组自有PID/creation UTC ticks全部退出，精确TMP/短UDS已清。未启动Runtime、provider、SSH或DPAPI，未删原数据。

工作树保留迁移和其它任务修改，不能以干净HEAD替代实际产品。本地提交仅包含本记录、[精确增量补丁](patches/AGENT-APPROVAL-EFFICIENCY-193.patch)及该补丁路径的 `-text` 属性；补丁相对本轮七份before原字节，不包含其先前dirty增量。父实际Git apply正逆应用均逐字还原，scratch已清。没有推送。

最终source manifest含3,299项，仅七个约定owner变化，SHA256 `3e279739b14bd44262cb147095803e65ac7df303cda6decd074c05f672623bc0`；patch SHA256 `c1e5091423d42c99399a811c0587eb564319f1eb9c9a67f3cd9d10f6229d5d9c`。原件根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/`：

- `agent-efficiency-optimization-193/implementation/sealed-implementation193.json`：`ea579772b804316d01e326b7da8ba5714f2fe6e408c6069b66ce4fdd4716d641`；父核93个原件和当前3299项，`parent-verification193.json`：`9124e8b2a6001a625b2cecfede2a9046701056fd72f45fe80b8364c50eeec93f`。
- `approval-efficiency-package-193/parent-package-verification193.json`：`f1231d48297ffedf255291ca9b9d150eac78e89bb94d79cd7da83ddbd502f4f6`，独核21个打包原件、构件及物理闭合。

保留所有原FAIL、DENY、UNKNOWN、调查和正式App。194仅为后续task-board真实产品与用量测量；必须重新绑定最终Git、源码、JAR、实际宿主classpath、原人类许可与主机指纹，再经产品自主部署、独立审批、真实浏览器业务及STOP/DISABLE收尾。服务器已暖、模型动作数可能不同，实际总差额不能全归因代码。其余七项目、冻结后的全新服务器八项目、非默认目录与整机重启恢复均未因此通过。
