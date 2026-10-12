# Agent 原生网页入口契约修复21

19-01纯主Agent的构建、发布、健康集合、平台自然结清均完成且APP0，但frontend正式Endpoint为TCP、browser为空，未通过授权HTTP publicUrl断言。根因是Agent原生plan的端口只有id/host/target/exposure，parser无条件TCP/空accessUrl、encode也丢协议和网址；模型不能表达HTTP/HTTPS。原网络约束只核EXTERNAL同hostPort，无法保证网页身份。不能将该轮说为完整纯部署成功、主模型卡死或审批阻塞；TCP健康也不证明HTTP业务。

本次仅改Agent独立包的四个生产Java与两个直接Tests。原生端口明确要求id/protocol/host/target/exposure/accessUrl，协议沿用已有HTTP/HTTPS/TCP/UDP，parse校验合法URL及协议/有效hostPort，encode完整保留声明，工具Schema/描述同步。网页要求必须匹配EXTERNAL同端口、同HTTP/HTTPS协议和URI相等的非空accessUrl；不推断协议、不静默改路径或URL。无publicUrl的CLI/TCP/UDP仍独立，PROCESS不提供端口转发的原门保留。

没有添加业务工具调用顺序或模型特化，没有修改shared core、生产审批、Skill/resources、Config、live/MainOnlyFixture、数据库或原冻结计划。新原生缺字段输入会被拒绝，未增加兼容分支。既有typed TCP存储codec未改，直接夹具读入/再编码字节一致；此证明不能替代原16真实恢复或Linux重启。数据库保持v1，无迁移、无旧记录改写。

定点验证显式限定Maven -pl src/mod/deployment -am -Dtest=... test：focused-01的Protocol五个直接方法15个XML逻辑case全部通过，Network原夹具缺16已required workspace而13FAIL/20ERROR，均未进入目标校验。只补测试夹具AUTO workspace，保留生产guard和失败证据，focused-02只重跑Network类33项全部通过。最新48逻辑case PASS，不重复已通过15，不运行全模块/全项目。覆盖四协议Schema/parse/encode、19同端口TCP反例、缺网址/host/path/query/scheme不符拒绝、非法URL/默认端口/IPv6、PROCESS/容器映射、无网页服务及旧typed TCP读取。

6Java IDEA格式write/check通过，两轮3279源码冻结零变化；0真实provider/SSH/Runtime/原DB。父核全部原前后像及当前字节，只有六owner、非owner/增删0；独立前像正向及当前反向补丁字节验证通过，正常用户CIM自有验证进程0。两个原像等于Git HEAD的owner可独立提交，其余四个迁移混合文件只交付[精确增量](patches/DEPLOYMENT-AGENT-HTTP-PLAN-CONTRACT-21.patch)，完整六owner补丁SHA256为32449e605293e7ef5608c01074358440bea5377914d89dcb2598fd16656e62a0。该增量基于本阶段完整工作区前像，未证明独立干净checkout构建，不整体暂存原迁移，不默认推送。

下一真实轮task-board-main-only-21-01使用新任务、新App、独立publicUrl46300，审批发送前硬拒绝、Runtime close后APP0。主Agent自行决定调查与工具/命令，监督只观察实际阻塞、通过代码/Skill修复，不接手SSH或提供逐步部署答案。源码/Skill须全程冻结；先验证自然部署与正式HTTP身份，不混入业务QA、浏览器或最终冻结八项目。真实新轮尚未启动，不能将48本地case当真机成功。

旧19失败记录、16发布应用与UNKNOWN、未结清明细和调查保留。成功恢复历史清理、完整task-board业务/浏览器/工具和双Agent、非默认目录真机/重启、其余七项目及最终冻结新服务器八项目仍待验收。21使用GPT-6.1-Sol，未使用GPT-6-Astra；证据位于.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-http-plan-contract-21。
