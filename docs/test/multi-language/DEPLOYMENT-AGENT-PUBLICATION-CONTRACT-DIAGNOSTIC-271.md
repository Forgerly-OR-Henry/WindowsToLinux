# 原238原生合同只读诊断271：本地定点通过，尚未实服执行

新增固定内部操作 `deployment.agent.publication-contract-diagnostic.v1`，定位原238当前参数、managed-data links及unit渲染合同。它绑定旧272 FAILED/UNKNOWN、完整385–391接续回执、较新的417–426未达目标整图及历史六指纹。原238仍UNKNOWN/pending；本阶段没有打开其Runtime/DPAPI或执行模型、SSH、回滚、安装、清理及新FULL。未改变数据库表、公开产品API或UI。

## 行为及审批边界

新REQUEST按原pending intent和原continuation REQUEST只占用一次；DENY、NOT_SENT、部分回执、超时、UNKNOWN及版本变更均不能重置。完整实际launcher/stdin、目标、历史绑定仍走独立APP及新的单次host grant，审批、grant及发送前复核原身份和当前证据。新日志保留REQUEST/REVIEW/GRANT/DISPATCH/RESULT/分块回执/END及中断事实，跨旧协议拒绝action/grant复用，并抬高后续整图必须跨越的事件屏障。

固定程序仅只读打开既有两锁，在锁内校验当前资源、历史六指纹及稳定性，直接运行原生`load_deployment_parameters`、`assert_managed_data_links`及`expected_deployment_unit_digest`。SDK环境守门保持；Python以独立继承pipe收集固定控制帧，并发、有界排空四路IO，超时只终止自己的进程组。控制帧不依赖stdout，避免原生重定向、管道或命令替换吞掉拒绝。系统查询先独立赋值，查询失败不能以空值相等或空输出判通过。

安全回执固定9字段、3项检查及有限状态；Java拒绝未知字段、重放、不完整控制事实和两层UNKNOWN退出码矛盾。三项PASS及OBSERVED只描述当前诊断，不是回滚结果、goal、ACK或结清权限。本次调用一律UNKNOWN退出，不进入OBSERVED_ONLY、cleanup或归档；后续独立协调仍须新的完整整图目标证明。

## 实际验证与保留的失败

11个声明路径变化：8生产、3测试；6既有、5新增。3337源，最终manifest SHA256 `23b159357f334d3f91b0fd6e7050092bacc2d4729b3ffee155c3605cc94f4eee`。原helper、已安装Program、旧v1/v2、Continuation及其资源未改。

- 首轮session57925：2026-10-17T02:28:36.3206691Z—02:31:56.6307032Z，9个精确方法、27例为25P/0F/2E/0skip；未打包。Archive22例、Evidence2例及Program身份1例通过。两错误均在新测试夹具`control()`把Python输出交Jackson时产生，原stdout/XML完整保留；首真正JUnit证据SHA256 `fcab1642a82222e53fd6ded71014359217db718599d7a62ce0040c5670ba198d`。
- 仅修改新测试的`control()`夹具：完整资源改经stdin JSON传输，短`-c`入口先检查子进程退出码。一次有界Windows Python实验的旧/新两子例均成功，未复现Java/Windows原始传参错误，不能据此声称其精确原因已证实。生产代码和其他3336源逐SHA不变。
- 第二轮session31907：2026-10-17T02:35:11.3734787Z—02:35:44.5592277Z，仅重跑原2个失败方法，2P/0F/0E/0skip，随后`-DskipTests package`成功。未重跑已通过25例。按方法直接依赖合并，最终9方法27例有效通过；全部原始JUnit为29例、27P/0F/2E/0skip。不是全套测试。

新Bash方法内7个受控场景经过真实原生函数，覆盖stdout重定向、管道/命令替换中的拒绝、普通错误、未知拒绝参数以及两个系统查询失败。仅最低层ownership及指定查询故障由夹具替代；先前合成PASS帧不代表合法完整Linux合同链。Windows/Git Bash通过不证明Linux `pass_fds/selectors/killpg`、真实root/flock/systemd及完整正向链，后续仍须产品实服只读诊断。

## 产物、身份与收尾

实际字节metadata核验通过：62 deployment/3 AI/4 platform/5完整host族，54个classpath入口、3294文件；deployment.jar 4081008B/SHA256 `16dd5834e657e382161af2c7138286a67e17a32ae121b7c1579fd61835c98b49`，其余5个host JAR保持原字节。metadata SHA256 `e8c970f0a09f91f5a2e88dbd026cea3a3666df906bd9ae1bebcd528db4f32b47`；CP SHA256 `47335121682ae9ba1093e56463820d03909de336c859e0bdb301486c8f4f955b`。实际JAR成员计数及checked entries重新测量，未继承旧便利计数。

纯byte身份builder实际0，沿sealed255/263/266原锚核旧Program/Helper/原测试/十资源、Layout及旧诊断/接续完整族，并核新11路径的source/target/JAR/CP。proof SHA256 `644a37f92653446c89b04e2469cb50feca8fce9e4f69803e0f4dd7c4f5018f72`，verification SHA256 `bff9408dea4505f8d5103820b0c044b916ebf86b000000e3ddc7f5732791709a`。未重跑旧Program JUnit；这些是字节及原测试接续证据，不是JVM CodeSource或远端执行证明。

两轮normal close分别核15/33个精确进程身份全退，独立audit分别核16/34个；总finalizer52个、独立最终audit53个全退，4个自有临时目录已删，执行开关关闭，无无关进程终止。最终audit首次因父漏传已观测退出码的事实开关而在CIM前拒绝，失败记录保留，补齐真实已观测0事实后通过；未修改守门。

正式patch SHA256 `a813171d2eb638691c5a22ae7e5542f93c925bc51d941356ff5ef321e72e206b`，151448B、11路径；所有hunk按捕获preimage独立重建，`git apply --numstat`计数核对。迁移工作区按既有交付约定仅提交精确patch及本文，保留其他未提交变动，不推送。证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-publication-contract-diagnostic-271`。

下一阶段才准备原238一次产品只读诊断，仍须独立APP及新grant，不重发旧272或385、不重装READY266。File Transfer后才处理CSV Inspector；两项完整BUSINESS v2、真实浏览器及必要收尾完成后暂停，分别报告FULL审批IO占比与加权缓存命中率及known/unknown，失败FULL/恢复窗口独列，不启动第三项目。194/234不恢复，221不自动恢复；当前仍9次历史FULL、0完整BUSINESS。
