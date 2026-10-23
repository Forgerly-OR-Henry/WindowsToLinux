# 重装后 ASSISTED 文件传输续验

新主机已通过产品 SSH 认证与只读预检。新机首次完整文件传输验收发布成功，但业务在取消上传后的 complete 请求发生 EOF，整体 FAILED；已由产品停止并核验 STOPPED/DISABLED。早409请求体收尾已完成本地反例、修复与定点验证，但第二次完整验收在首个下载后创建第二个上传时再次EOF，仅1/5下载。两轮业务均FAILED并已STOP/DISABLED，正在核对连接关闭时序，尚无完整PASS。

本轮只继续文件传输；看板、资产借还、CSV 的既有通过属于旧主机历史，未在重装后的主机重新验收。未执行八项整轮或 Agent 真实部署。

## 新连接与服务器边界

使用主数据库唯一指定连接 `server-8e036e3c-f31`，目标 `45.192.105.213:22`，账户 root；实际新指纹与已存 `SHA256:xLZ9+SvNCri7l3uXB7WH3cqz2ZS5uugccI6aJru5O3M` 一致。产品认证任务 `0ac23a12-7238-4b51-90e3-a675d88cc4ac` SUCCEEDED；独立新保护连接状态已建立，主库及 bootstrap 前后 SHA、大小、修改时间不变。

本地主密码框曾进入等待，但 READY 不能证明窗口可见；用户随后反馈未看到窗口。用户明确授权直接使用其提供并更正的主密码后，改用禁回显的 `Console.readPassword()` 完成解锁。密码未写入文件、命令参数、日志或本报告。早期本地 JSON 整数节点比较误拒绝、输入 EOF 和等待进程关闭各自保留，均未启动 SSH，不计作服务器认证失败。

服务器预检只读。第一轮通配 systemd 查询退出1，原因未证实；改为完整查询成功后本地过滤，第二轮退出0。约27.7GB可用、无旧管理服务或旧发布目录，监听仅SSH与本地DNS，因此未删除任何旧发布。后续服务器安装、发布、业务及生命周期写入均由产品执行；人工观察仅只读。

## 新机首次完整业务：FAILED

`file-transfer-new-host-01`：Maven exit1，JUnit 1项、0 failures、1 error、0 skipped。发布任务 `a6014994-de8b-4699-9ded-cd25ca50b7e5` SUCCEEDED；应用 `wtl-assisted-file-transfer-2j2e9gquc`。使用正式1MiB分片、64MiB和两版本数据，前三次64MiB下载完整通过字节数/SHA校验，分别耗时75693、76627、76041ms。最终V2门槛、后两次下载和实际管理导出未完成。

错误为：取消上传后，1MiB分片 PUT 如期409；紧接着空 POST complete 应409，却在读响应头时 `Unexpected end of file from server`。原journal显示Go在0.49ms返回409、未读请求体；Node只观察到5792B时已经完成下游409，但 incoming request未完成，之后未见REQUEST_END。未见panic或异常重启证据。

产品finally STOP任务 `28200e67-fb34-40c5-ae4f-81a8e7d63a7b` SUCCEEDED，两次inspect核验STOPPED/DISABLED。真实systemd单元为 `windowstolinux-{unitId}.service`，事后只读核验两项均inactive/dead/disabled、MainPID0、FragmentPath匹配。应用和日志保留，未删数据，也未追加重放STOP。清理回执为本地 `tools/file-transfer-new-host-01-finally-cleanup-receipt.json`。

原状态持久只读审计确认：15个已知任务归档均ENDED/SUCCEEDED，保留执行行、UNKNOWN及未释放资源均0。任务归档成功不改变业务FAILED结论。用量去重74/74次生成匹配，已知输入868024、输出50901，合计918925 Token；4项能力探测的Token和物理请求数未知，费用未知。

两条SUBMISSION_REJECTED属于一次返回两个submit_result而被整批拒绝，未执行动作；helper前置probe退出64、后续独立批准安装命令退出0，前置守卫的具体触发原因未证实。列表中的未观测UNKNOWN快照与执行UNKNOWN分开记录。

## 取消链路修复与本地验证

真实TCP反例复现了早409后请求体留在暂停状态：先发送5792B，收到合法409，再补足1MiB并在同连接发送complete，旧实现只解析到第一条请求并超出3秒本地检查期限。此反例与原journal观察一致，确认了Node早响应后的请求体收尾缺陷；不能据此宣称已证明底层每个EOF报文细节。

修复在上游完整结束、JSON合同有效后解除原pipe，恢复读取剩余请求体并关闭已结束的代理；已结束的下游响应不再被旧代理错误销毁。持久HTTP连接需要读完请求体或正确关闭连接，见 [RFC 9112 第9.3节](https://www.rfc-editor.org/rfc/rfc9112.html#section-9.3)。未关闭全局keepalive、未增加重试或超时，也未减小正式业务规模。

| 定点检查 | 实际结果 |
| --- | --- |
| 早409真实TCP反例：补足请求体后同连接complete | 修复前失败；修复后两个409、原请求complete=true |
| 正常上传/处理阶段与断连观察 | 1项通过；断连合同允许502或ECONNRESET，不能单独声称必返502 |
| 64MiB下载背压与断连诊断 | 2项通过 |
| 真实Node→Go取消 `TestUploadObservationCancellationThroughNode` | 通过；copiedBytes32768、contextCancelled=true、已提交分片0 |
| server-only TypeScript，原lock TS5.9.3/@typesNode24.10.1 | `tsc -p tsconfig.server.json` exit0；5输入前后不变，无client/full build |
| 三路径增量独立应用 | `git apply --check` 与实际应用均0，输出逐字节与修复源码一致 |

原取消测试的80ms定时存在先取消再读到首字节的竞争，本轮首次检查因此copiedBytes0失败。改为父进程看到真实STAGING_FIRST_WRITE_END后经stdin通知Node取消；Node显式 `--test-isolation=none` 使通知到达该测试进程。3秒检查和12秒进程期限未增加。该测试调整的中间失败、本地路径准备错误及独立补丁目录继承CRLF的失败校验均保留，没有覆盖成首轮全过。

## 第二次完整验收与交付范围

第二次使用新任务、新应用、独立数据，入口仍为 `DeploymentAssistedRegressionLiveAcceptanceTest#deploysAssistedWebsitesForManualAcceptance`，仅 `file-transfer`。正式标准仍是1MiB分片、64MiB、两个版本、五次完整下载、十并发、重启持久化、错误/取消、真实后端502、实际管理导出、STOP/禁自启；产品浏览器客户端连续30秒无收到非空数据才中止；Java HTTP验收仍沿用20秒socket读取期限，未改为30秒，不能混称两套实现。预算、模型和审批规则不变。

run02 attempt01因父遗漏预建本地delivery目录，在deliveryFile.toRealPath前置检查退出1，未进入runtime、SSH或模型。原log/exit/JUnit已保留；补齐目录后的attempt02才是真实新任务。attempt02已经实际退出1，完整结果见下文；未因失败继续循环重部署。

source09的2363路径先核对零漂移，再仅复制本次3个受管源码/测试路径至隔离f24，其余2360路径、fixture、helper和配置未变。source10 SHA `c06fe01b9bd3ef5cadc692a1203e0fca321b572c0bb202000eb9f143c45685f1`。原主树与f24都保留已验证修复。

本次沿用已确认的混合工作区交付方式：[三路径增量补丁](patches/ASSISTED-RESUME-CANCELLATION-20261024.patch) 和 [逐文件源码绑定](ASSISTED-RESUME-CANCELLATION-20261024-SOURCE.json)，以source09原字节为基线，不可称为对当前HEAD可独立应用的全部源码。补丁SHA `aad54bb78a399fe440c131f4c162e834ba79c894cf04118e9183db92e997425e`，7570B；前置生产修复仍见 [原交接](ASSISTED-FOUR-FIXES-20261023-HANDOFF.md)。不夹带其他任务的已暂存或未提交修改，不推送。

辅助证据位于 `.ai-workspace/evidence/assisted-resume-20261024/`；进度入口 `.ai-workspace/progress/CURRENT.md`。临时Node监听及TypeScript安装工作目录已关闭/删除；两轮正式验收及其所属进程均已核验退出。子任务按router使用Sol High/XHigh；追加EOF连接时序难题由Astra High做本地专项验证，未启用Ultra。

## 第二次完整结果：FAILED，首个下载后 create 响应 EOF

run02 attempt02发布成功，使用新应用 `wtl-assisted-file-transfer-3su3i9wts`、发布root `1cb3cbaa-3414-44e8-b4c3-860f387d2684`。正式业务越过中途重启和十并发complete检查；首个64MiB下载在93321ms完整通过SHA，但随后第二版本 `POST /api/uploads` 读取响应头Unexpected EOF（fixture9751→9812→9991），尚未进入本轮取消场景。Maven实际exit1，JUnit1项/0 failures/1 error/0 skipped，下载1/5，管理导出未完成。

产品finally STOP `5c1e208a-64e2-4f98-a141-503fc3d28d65` SUCCEEDED，两次inspect `5c339e3a-849c-4386-be04-7800d7a81c4c`、`6a761c89-38f3-4972-b704-2f4145760cdb` 均STOPPED/DISABLED；新只读观察两unit均inactive/dead/disabled/MainPID0，精确四个所属PID均退出。独立清理回执为 `tools/file-transfer-new-host-02-finally-cleanup-receipt.json`，SHA `f2491526647ca016a1ce074bff683eaada2cd5bcc28696888ff8bbb331bd363d`。未执行预备的额外STOP入口，未删发布或数据。

该state持久只读审计15个已知任务均归档ENDED/SUCCEEDED，执行行、UNKNOWN、未释放资源0；stdout/持久证据72/72匹配。已知输入632218、输出53584，合计685802 Token，另4能力探测用量和物理请求数未知。新机两次真实运行已知合计1604727 Token、146个已识别生成尝试，8项能力探测及Codex用量、费用未知；这不是整个历史修复任务的全部用量。

本轮5条SUBMISSION_REJECTED为1条JSON语法校验和两批各两个submit_result的执行前拒绝，不属于5次远端失败。179个已记录远端命令结果均exit0；完整业务仍FAILED。用量finalizer的本地退出字段适配失败及重做也分别保留，未改写为初次成功。

原日志显示Node在约84.9秒完成完整64MiB响应写入，客户端约93.3秒才完整返回，存在约8秒排队差异；没有panic或异常重启证据。该时差超过默认keepalive计时本身不足以定因。原早409请求体缺陷由独立本地反例证实，但不能解释第二轮尚未到取消便发生的EOF。当前仅在本地核对真实Java缓存连接、服务端FIN与客户端收到字节的时序，没有追加第三轮部署或延长超时换取通过。
