# 183：正式模型服务单次 TEXT 能力验证通过

本轮只在全新独立数据库中，通过正式 `PlatformRuntime.models().save` 保存 `live-deployment`，再调用一次 `verify(TEXT)`。实际 Task `4c1ff09c-b254-42c7-808c-902c51b1624f` 为 **ENDED / SUCCEEDED**；Java exit0，`Runtime.close` 正常完成。它证明本次保存配置下的正式 TEXT 能力探测通过，不是完整task-board部署验收。

公开配置固定为 `https://api.deepseek.com/chat/completions`、`deepseek-flash`、LOW，产品探测预算仍为30秒、8192输出。没有APP、TOOL、FULL、SSH、业务部署、恢复或自动重试；未重开178原UNKNOWN Runtime。API key只从既有本地文件有界读入Java内存，再交正式加密vault保存，不进入参数、环境、提示正文或日志，不输出或hash原key。

## 实际结果与用量边界

正式 `verify(TEXT)` 返回Void成功结果，没有公开token用量或HTTP阶段值；本轮input/output/reasoning/cache usage、底层物理请求次数及完整账单均为 **UNKNOWN**，不能填0。一次平台verify调用不等于已证底层仅一次物理请求。成功结果没有Problem，因而没有可投影的 `httpHeadersReceived/httpStatus/httpBodyBytesAccepted`；不得反推具体状态码、完整正文长度或处理时序。

父从已审核runner唯一启动并观测退出，再执行正常close脚本。本轮没有JUnit测试或Maven调用；182此前六个定点方法和显式skipTests打包是独立证据，没有为183重跑。新结果不解释178原30秒超时的外部原因，原178任务及用量UNKNOWN保留，不据此改写旧状态或追补旧账单。

## 最终源码和运行代码绑定

实际入口绑定HEAD `6a7108947414d09e27dc3be6fa0af2fe8f49b563`；3299项源码与182 after完全相同，manifest SHA为 `464a949dfa5e276e5544e18127ea575524452ec3e76724a7449cdbb3ccb193d3`。原consumer编译类复用，没有再次编译产品或consumer。

39项classpath（27个依赖JAR、12个类目录）的1041个实际文件均冻结，并在实际前后核对。实际Java以不请求类初始化的方式核对两个HTTP类，codeSource均为 `file:/I:/Program/WindowsToLinux/src/app/ai/target/classes/`：

| 运行类 | SHA256 |
|---|---|
| `ModelHttpCall.class` | `5f2f45c8ddf0bc2afd09786e7c4bd8803e6978c7197dbab3146a0bf07480a43c` |
| `LimitedHttpBodySubscriber.class` | `b3ac78b0599f05c36bf5e529292cdc78dd1d488cbadeaeb7776800ef9db6c603` |

`deployment.jar` SHA为 `a5c72b9eac6735962b57ad6608309f08f94c089438c85e7862181d74c268bf65`，宿主app-ai JAR SHA为 `a7d283d6bd8fa23ad415d9ef8729e7e6269ee60cf8845f65b87a478760119353`。两个宿主HTTP类的独立绑定避免仅凭mod JAR推定实际宿主代码来源。

## 保护、物理收尾与保留

全新根 `src/app/db/data/agent-live-text-capability183-86cb813e60ed4355addbf86e5d77bb42` 保留正式模型/check/task数据库。实际使用CurrentUser DPAPI保护bootstrap及加密vault；根DACL只允许当前用户与SYSTEM，前后回读通过。公共fixture vault解锁常量不等同于API key。

正常CIM核本轮launcher和Java的PID及creation UTC ticks均已退出，精确自有TMP和短UDS已清；封存阶段另一次正常CIM独立复核两进程已退、路径不存在、owner marker及私有DACL仍符合。没有删除数据库、结清UNKNOWN或修改任务状态。独立metadata工具及其临时类/TMP此前已经闭合；本次封存未发网络请求、未打开数据库。

证据目录为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/single-text-capability-183/`：`actual-01/text-result.json`、`runtime-close.json`、`loaded-http-classes.json`、`closure.json`及独立物理复核保存原实际事实；consumer stdout/stderr均为空文件，长度和SHA照原件保留。原草稿与授权false字节、`final-offline183.json`、父独立最终审核和后续唯一actual授权true均保留，不能把原离线seal的等待时点改写成实际执行后状态。最终实际封存见同目录 `sealed-actual183.json`。
