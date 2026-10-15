# 200：容量创建前拒绝的有限结清

**最终有效35个定点case通过，非全量；唯一显式skipTests打包通过。** 本阶段没有真实SSH、provider或业务验收，不证明审批占比、缓存或费用已改善。它属于用户新指定三轮优化中的第一轮前置修复：已完成优化轮数仍为0/3，第一轮在途。

## 已确认的阻塞

[199失败窗口](DEPLOYMENT-AGENT-OBSERVATION-LIVE-199.md)的原公开回执为Task `60820c9e-97e8-43a2-a9d3-09febb499cc8` UNKNOWN、Goal BLOCKED。JAVA prepare的原完整命令结果是FAILED/exit65、未截断、stderr空：VOLUME可用7,734,480,896字节，小于固定8 GiB隔离卷加1 GiB预留所需9,663,676,416字节。该已发送失败留下未释放checkpoint；后续NODE resolver成功，新的controller readiness被原未确认动作守门阻止。没有证据表明这是State持久化故障。

固定controller在容量检查前可能准备共享ROOT目录及`.prepare.lock`，但本次operation目录、镜像、loop、挂载、账户和worker都在该检查之后创建。异常发生在`agent_create`返回之前，后续cleanup try尚未进入。因此既不能记为NOT_SENT，也不能宣称完全无副作用或cleanupComplete。工具链固定卷与模型动态选择的业务工作区配额是两个机制。

## 最小实现与边界

仅改Agent的Commands、ProgramState、FailureEvidence及前两者测试，共5个owner。controller/loader/manifest、8 GiB卷、1 GiB预留、配置、独立审批及其它模式编排不变。

只有未来当前活stored调用可以生成私有typed proof：原未released INTENT、同Action/task/server/captured layout/version/path/source、原exactAction与request SHA、operation及PREPARE/BIND都须一致。已审controller源码另以独立字面量钉死SHA `2f82c5c50834f747ff005832edafbc47e9bed72103a98a538a8242d8ed4f3c19`和101,904字节，不以未来动态renderer相等自动继承本证明。

原SDK结果必须完整FAILED、exit65、无截断、stderr真正为空；stdout恰为`isolation:HOST_SPACE_REQUIRED`失败行与唯一严格规范JSON诊断行。只接受VOLUME、原operation、固定容量策略及实际shortfall。重复或额外字段、尾随token、额外行、不同请求/来源/身份、PROMOTION、INSTALL、取消、UNKNOWN、缺失退出等均不能释放。

状态仍为FAILED，保留原回执与诊断。有限含义仅是“本次operation私有资源尚未创建；共享目录和prepare lock保留”。先持久化原结果及proof，再写匹配payload摘要的RESULT事件，最后释放该checkpoint占用。snapshot同时严格复核原index、proof与唯一RESULT；advance、journal或close失败仍阻塞。下一动作仍须独立审阅完整当前请求、取得新grant，不能重用旧授权。

不同rendered source，包括尚未单独审定的非默认layout源码，不自动采用此证明；历史版本也不与今日current版本机械比较。旧199及其它FAILED/UNKNOWN不从日志补证或追溯结清，不自动重放或删除资源。既有strict JSON mapper和旧retain/diagnostic方法体相对本轮实际dirty bytes未变。

## 实际验证与原错误

| 本地尝试 | 实际范围 | 结果 |
|---|---|---|
| validation | 原定5具体方法 | production compile漏捕获checkpoint snapshot的ApiException，0case；旧XML被守门拒绝 |
| validation-02 | 同一方法集的运行前检查 | 上次编译清掉部分class输出，完整构件预检拒绝；Maven未启动，0case |
| validation-03 | Commands两方法、State三方法，共35case | 34 PASS、1 ERROR、0 FAIL/SKIP |
| validation-04 | 只重失败的State参数方法31case | 31 PASS、0 ERROR/FAIL/SKIP |

编译错误只增加取证异常的捕获，取证失败不生成proof。唯一测试ERROR是SUCCEEDED反例夹具误带Problem，被API构造约束在待测逻辑前拒绝；只修该参数的合法结果结构，生产三owner及其它owner逐SHA不变。其它已通过四方法复用，没有重跑。合计实际66次case为65 PASS、原1 ERROR；替换失败方法后最终有效35 PASS。原日志、XML、失败版本、manifest与patch均保留。

工具修正仅允许构建前缺少失败编译清掉的目标class：旧输入JAR仍逐SHA钉住，存在的未改class须相同；构建后仍严格要求全部未改类族及新owner完整。失败Maven不接受旧报告，成功仍要求精确selector、完整原XML/TXT、正确case数及0skip。局部编译、预检和夹具修补不消耗额外优化轮数。

覆盖当前受控JAVA拒绝后新RUST动作/新grant、PREPARE/BIND、原FAILED保留、durable顺序与三种失败、foreign task/source/request、原版本读取和未来来源拒绝、恶意或不完整回执等。采用原BuildCommandChannel/内存checkpoint及受控native fixture，不是实际模型、Linux隔离或远端业务证据。IDEA格式只对5owner初次执行及两次实际修正的单文件执行，write/check通过；没有扩大业务测试。

唯一打包命令为`-B -pl src/mod/deployment -am -DskipTests package`，Maven exit0、15.877秒，14处skip、0测试执行。最终JAR SHA：

`1bb4f3fd2c46ec41778d997f66d85ca75072f230d8a7bbe37cbe64edac906918`

3个当前生产类族的全部7成员与target/JAR逐字一致；196原17组精确排除本轮Commands和ProgramState两组，其余15组保持，FailureEvidence另按新owner核验。198 policy、SourceBrowser/InputCoverage/NativeHistory、hostAI/两HTTP类和真实验收fixture保持原字节。

## 隔离交付与后续

实际源码清单3307项，仅5owner变化；本地提交只含本记录、[相对本轮原dirty bytes的精确补丁](patches/AGENT-CONTROLLER-PRECREATION-200.patch)及该单路径`-text`属性。父在自有scratch实际Git apply正逆并逐字核5owner，scratch已清；不整体暂存混合迁移源码，不声称干净HEAD可构建当前产品，不推送。

正常用户CIM核四次工具调用分别10/3/15/16组PID与creation ticks、打包12组均退出，精确marker TMP/UDS均清。原产品数据删除0。实现格式进程另外逐轮核退；未启动真实Runtime、浏览器、SSH或模型请求。

原件根为`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/controller-precreation-refusal-200/`：

- 最终source manifest：`e63ca098cd26e2099d00c1b8bfe9705abc2cf9f338ad7597dddc2f6a25752091`；精确patch：`6e47dccdcb902e0ddc3ec138103ec213cab9b592b961ef6c0156be4596b2f98c`。
- package after metadata：`a23c696d555a4d4e1471baf62d42e757069b6f44ec6a9f48b3c294f669c05140`。
- 父交付核验：`4fcdb08431225f731e56e6b1acec20512bab0dead2ff176f034ed5700342260d`；347份原件seal：`e56b131adf156bc2c1218e997ef4d068eb9da53ad9f2e0fbc48e6873b01c0066`。

Sol XHigh实现与具体修正，Sol High准备本地及恢复工具；Astra仅判断创建边界与容量策略两个耦合问题，不代替实际测试。下一步先按归属/依赖及独立审批评估原197候选的产品清理，再由新轮产品模型重新观测来源、容量和连接。当前容量及200真实效果尚未验证，不盲目放宽卷或预留。

三轮上限与最低cache≥60%/APP≤20%、理想≥80%/<10%保持；完整业务、真实browser及产品STOP/DISABLE和同覆盖实际provider数据才能算达标。三轮仍未达到最低线即暂停，不以失败截面或本地载荷字节替代。其余七项目、冻结后全新机八项目、非默认目录真实部署、整机重启及旧未结资源安全结清仍待。
