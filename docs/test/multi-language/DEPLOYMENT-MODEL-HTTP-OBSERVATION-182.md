# 182：模型 HTTP 失败的安全阶段观测

178的首个TEXT能力探测在29956ms后耗尽30秒请求预算，原任务为UNKNOWN。179只读取证没有发现HTTP状态或响应阶段证明；181随后一次匿名GET `/models`在2.483s收到401并丢弃完整正文，只能证明当时基本HTTPS可达。原UNKNOWN、用量及外部原因保持原状。本改动为后续请求补齐阶段证据，不将其表述为178外部根因已修复。

## 实际行为

`ModelHttpCall`仅在JDK BodyHandler实际观察到最终响应状态后发布状态。在原失败Problem.context中增加三个固定安全字段：

| 字段 | 含义与边界 |
|---|---|
| `httpHeadersReceived` | 失败快照时是否已发布实际响应头状态；false不证明请求未发送 |
| `httpStatus` | 已收到头时的实际JDK状态码；未观察到头时不提供 |
| `httpBodyBytesAccepted` | 完整onNext批次通过原有大小限制后累计接受的正文字节；不含头或被拒批次，不证明完整响应或提供者处理结果 |

复用 `LimitedHttpBodySubscriber` 的同一限额检查。其计数对观察线程volatile可见，整批校验后才提交接受字节数，避免把被拒批次的部分计入已接受结果。没有保存响应正文、认证头、API key或完整异常消息。

探测30秒、8192输出、reasoning、协议及STATIC/ASSISTED/AGENT编排不变。UNKNOWN、取消、单次execute/send入口和实际关闭语义不变；没有新增重放或降级，也不从新字段追补178旧请求证明。

## 定点验证

仅运行以下六个方法，**6 PASS、0 FAIL、0 ERROR、0 SKIP，Maven exit0**，没有全类、模块或全项目测试：

```text
ModelHttpCallTest#incompleteRealResponseRemainsUnknownAndCannotBeSentAgain
ModelHttpCallTest#cancellationBeforeResponseHeadersRetainsUnknownAndClosesWithoutReplay
ModelHttpCallTest#deadlineBeforeResponseHeadersRecordsNoHeaderObservationWithoutReplay
ModelHttpCallTest#deadlineAfterResponseHeadersRecordsAcceptedBodyBytesWithoutReplay
LimitedHttpBodySubscriberTest#acceptsExactLimitAndRejectsOneAdditionalByte
LimitedHttpBodySubscriberTest#countsAllBuffersAndPreservesAValidResponse
```

Loopback覆盖无响应头超时、收到头及21B正文后停顿、取消、部分正文断流、拒绝再次执行、关闭，以及精确边界/多buffer计数。没有真实Runtime、provider或SSH。四个owner的定点格式write/check均exit0；ModelHttpCall原CRLF由该格式检查规范为LF，精确字节补丁包含此变化。

原测试runner在Maven成功后，因aux capture把 `test-01-before-manifest.json` 拼成另一个文件名而exit1/sourceFreeze1。原结果与日志保留；只修这处路径并重做失败的after capture，未重复已通过的六方法，也未再改产品/测试字节。修复后的capture及最终实时清单均通过。

随后单次执行 `-B -pl src/mod/deployment -am -DskipTests package`，Maven exit0、21.146s；日志14处明确跳过测试，没有执行测试的记录。打包前后3299项源码与182 after一致。正常CIM核12组自有PID与CreationTicks全部退出，精确自有TMP和短UDS已清。

最终 `deployment.jar` SHA为 `a5c72b9eac6735962b57ad6608309f08f94c089438c85e7862181d74c268bf65`；宿主 `windowstolinux-app-ai-1.0.0.jar` SHA为 `a7d283d6bd8fa23ad415d9ef8729e7e6269ee60cf8845f65b87a478760119353`。宿主JAR内HTTP类与target字节一致：`ModelHttpCall.class` SHA为 `5f2f45c8ddf0bc2afd09786e7c4bd8803e6978c7197dbab3146a0bf07480a43c`，`LimitedHttpBodySubscriber.class` SHA为 `b3ac78b0599f05c36bf5e529292cdc78dd1d488cbadeaeb7776800ef9db6c603`。原验收fixture class SHA `b300a41c48dda597a562b82d6c6bb047f4cffa7d59d6861b3eecfe489affd421` 不变。打包证据位于同级 `model-http-package-182/`，seal SHA为 `43f314edd3b2a93e93d05702e7be20d595df20f46549bfeb57f4a8ef746464bb`；父独立核全部原文件及当前五个构件，通过。

## 源码隔离与证据

实际工作树保留原迁移及其它任务改动，部分源码不在HEAD。因此本地提交采用本记录与[精确增量补丁](patches/MODEL-HTTP-OBSERVATION-182.patch)，补丁相对本轮before原字节，不声称可在缺少原迁移的干净HEAD直接构建。

该补丁包含原CRLF行的删除及LF行的新增，`.gitattributes` 仅对此精确路径设置 `-text`，防统一LF规则改写补丁原字节。补丁中的空白上下文行属于unified diff语法，检查时仅对此文件关闭行尾空白告警；正文与属性文件按默认空白规则检查。

只改变四个owner：`ModelHttpCall.java`、`LimitedHttpBodySubscriber.java`及各自测试文件。3299项清单的before SHA为 `6bba98ed41eeaf56b4ec8ce1e30548a0ad373b7a6255187bf0ee14eea598c831`，after SHA为 `464a949dfa5e276e5544e18127ea575524452ec3e76724a7449cdbb3ccb193d3`；补丁SHA为 `fb7e4f75ed12254edddaa8713396c49869607342d10db8fb9472d9462eb04836`。父在新marker绑定scratch上实际执行Git apply正向、逆向check与应用，均精确还原四文件原/最终bytes，scratch已清，未据此覆盖真实工作树。

证据目录 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/model-http-observation-182/` 保留原log/XML、失败capture、四owner原bytes、前后清单、实际class SHA及精确patch。父独立核六方法原XML及四owner SHA，通过；正常CIM核15组自有PID+CreationTicks全退，精确自有TMP已清。

宿主层App-AI变动不能仅用deployment.jar单独证明。后续实际工具还须绑定并核对实际加载的 `ModelHttpCall.class`、`LimitedHttpBodySubscriber.class`、codeSource及完整运行classpath文件集合。183仅准备一次全新官方ModelService TEXT验证，不含SSH、APP、TOOL或业务部署；真实模型能力与完整task-board仍待验。
