# 203：已验证工具链的独立小卷探测

**本地定点最终有效15个Python方法、38个Java用例通过；唯一显式跳测打包通过。** 尚未运行新版真实产品部署，不能据此判断64 MiB是否足够、审批比例或提示词缓存是否改善。本阶段属于用户限定的第2轮优化；第1轮未达标，累计完成1/3，第2轮仍在进行。三轮仍未达到最低线即暂停，最低缓存≥60%／审批≤20%，理想≥80%／<10%保持。

## 原因与最小改动

[199](DEPLOYMENT-AGENT-OBSERVATION-LIVE-199.md)的实际VOLUME拒绝是可用7,734,480,896字节，小于8 GiB隔离卷加1 GiB余量。源码审查发现，prepare首次验证了既有安装，却丢弃选择结果；缓存探测和bind仍一律申请冷安装的8 GiB卷。[201原任务恢复](DEPLOYMENT-AGENT-ORIGINAL-RECOVERY-201.md)未成功，不能声称旧资源已释放或当前容量已足够。

本次仅修改Agent工具链的4个生产文件和5个直接测试文件：controller资源、安装审阅合同、完整源码manifest、FailureEvidence及所属测试。Commands、ProgramState、loader／installer模板、业务工作区配额、独立审批和其它两种部署模式的编排不改。

| 操作 | 隔离镜像 | 主机准入余量 | 限制 |
|---|---:|---:|---|
| 冷INSTALL | 原8 GiB | 原1 GiB | 保留原PROMOTION空间算法 |
| 已验证安装的PROBE／bind | 固定64 MiB候选 | 原1 GiB | 仅匹配的MANAGED或已注册SYSTEM；不能转INSTALL |

首次完整record、实际marker原字节SHA、受保护目录身份及JAVA_HOME被保存，并在worker前、worker内和成功回执前重新核对。缺失、记录或目录替换、SYSTEM文件变化均停止；不能选择另一记录、下载、促升、扩大配额或自动重发。最初缺失才可采用原冷安装策略。

运行manifest升为v2，严格绑定mode、精确bytes、请求与安装选择、镜像和挂载身份。fallocate、loop sizelimit及实际镜像长度使用同值；另读实际loop限制。[util-linux源码](https://kernel.googlesource.com/pub/scm/utils/util-linux/util-linux/+/refs/tags/v2.41.1/sys-utils/losetup.c)确认SIZELIMIT列输出整数字节，这不是目标主机版本或真实挂载验证。原DynamicUser、非root vendor执行、网络隔离、可写目录、stop／空cgroup／freeze／detach条件保持。数据库版本仍为1。

64 MiB是有界候选，未证明每种vendor探测都能容纳。capture先向卷内临时文件写入，8192字节限制在执行结束后读取时检查，不能据此推断运行时写入量。创建中途及bind失败仍可能留下原私有资源，不能自动记cleanupComplete；旧UNKNOWN和资源不变。

## 容量拒绝证明

沿用[200的有限结清](DEPLOYMENT-AGENT-PRECREATION-REFUSAL-200.md)，独立钉住已审完整默认源码，而非动态信任未来manifest：

- 原`2f82c5c50834f747ff005832edafbc47e9bed72103a98a538a8242d8ed4f3c19`／101,904字节，仅原9 GiB阈值；保留原历史proof读取。
- 新`7ce598b142448c3385f845426f3d61a8564f23ea4fc8e5a4b160f543c2035159`／108,038字节，仅9 GiB或1,140,850,688字节两档。它不同于裸controller资源的84,432字节。

两档VOLUME检查仍在本次operation目录和镜像分配之前。只允许原动作／任务／服务器／布局／请求／operation完整绑定、FAILED／exit65、未截断、真正空stderr及唯一规范两行诊断；仍保FAILED、共享目录和prepare lock。原结果与RESULT持久化后才释放对应checkpoint。任意源码、未审非默认render、PROMOTION、创建后失败、未知退出、取消或UNKNOWN均不借此结清，不追补旧199。

## 实际验证

| 尝试 | 范围 | 实际结果 |
|---|---|---|
| python-01 | 15个具体方法 | 11方法通过、4方法失败；日志5FAIL／5ERROR是subtest条目，0skip |
| python-02 | 仅上述4个失败方法 | 4通过、0失败／错误／跳过；其余11通过证据复用 |
| Java首次准入 | 同6方法的筛选组装 | 同一类重复列出，工具在目录／临时资源／Maven创建前拒绝，0用例 |
| java-02 | 4类内6个具体方法 | 38通过，0失败／错误／跳过；不是全类或全量 |
| package | `-B -pl src/mod/deployment -am -DskipTests package` | exit0，16.037秒，14处跳测、0测试执行 |

Python失败确认为Windows夹具混用WindowsPath与PurePosixPath导致原目录校验拒绝；部分PLACEHOLDER_BINDING是之后宽mock进入错误收尾造成的次生错误。仅四个方法按既有POSIX夹具方式修正，AST确认其它方法未变，另外8个owner逐字未变，生产守门和身份漂移反例均保留。原失败版本、日志和首封资料保留，没有重跑11个已通过方法。

Java核对真实renderer的完整pin、新旧阈值隔离、原durable结果先于release及33个不确定／foreign反例。实际XML为ProgramStore 1、FailureEvidence 2、ProgramState 34、Commands 1。Python是受控OS替身，不能冒充真实Linux／ext4／systemd验证。5个Java文件限定格式write／check通过。

全部实际运行的源码前后冻结检查均为0。正常用户CIM确认格式9组、Python两次7／6组、Java16组、打包12组自有PID／创建时间全部退出，精确marker TMP／短UDS已清。未启动产品Runtime、DPAPI、SSH、provider或浏览器，原产品数据删除0。父核验工具曾假设旧metadata含sourceSha256而遇KeyError，改为使用冻结全源码清单并核可用原字段后完成；没有改产品或重跑测试。

## 产物和下一步

最终`deployment.jar` SHA256为`690d52e5c0d2030aa8acada3708aabe4cf860fc2e8814dc6d015153b368127b3`。父独核19组生产构件共39成员的实际source／target／JAR对应关系及精确类族；宿主AI、HTTP、原验收fixture和ee40审批政策保持。打包一致不等于未来Runtime实际类路径已加载。

3307条最终源码仅原9个owner变化。[精确增量补丁](patches/AGENT-CACHED-PROBE-CAPACITY-203.patch)相对本阶段原dirty bytes，父实际Git apply正逆检查与逐字应用通过，临时scratch已清。仅本记录、精确补丁和单路径`-text`属性提交本地，保留混合迁移／用户改动，不推送，也不声称干净HEAD可独立构建。

原件根`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/cached-probe-capacity-203/`：

- 最终manifest：`b077894166df30b4d2a17081ac97b43b6a0c118ff81815002faa81c92e56f6b0`；patch：`2286f6b6e96f1a4f1f52a5028967b71a6d546aa72a4039767e1a039e0e7ca65b`。
- 实现seal：`1f60fc40393c18f7a81302941ef005ec0bd5908ad79a09e8b524d1de2638978a`；首版保于`implementation/before-python-fixture-fix-01/`。
- package metadata：`f7e809c347752b78472149846714f872e0552066ce022e50cdd6eab24ca121d5`；父最终核验`parent-delivery203.json`保实际定点、产物与物理收尾。

下一步至多一次本轮新task-board完整产品复验，必须重新绑定最终Git／源码／JAR／实际类路径及已有主机身份和出站许可。当前服务器容量、所需完整工具链cache／SYSTEM记录是否齐仍未知，冷缺失且不足9 GiB仍应拒绝。部署与审批模型自主操作，每次具体动作独立新grant；完整前后端、真实浏览器和产品STOP／DISABLE完成且同覆盖真实用量可核，才能判断本轮指标。工具链缓存与provider提示词缓存不同，不以本地字节或配额变化推导provider收益。其余七项目、冻结新机八项、非默认目录和重启恢复仍待。
