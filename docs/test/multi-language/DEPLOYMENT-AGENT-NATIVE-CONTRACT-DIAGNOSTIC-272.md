# 原238原生合同诊断协调272：仍 UNKNOWN，待核新journal

使用271已验证包，对原File Transfer 238执行一次有界产品协调。唯一产品session93272结束1；任务仍UNKNOWN，原ENDED断言失败。273账本尚未读取，新诊断的REQUEST、APP决定、fresh grant、dispatch及安全receipt均未知，不能据用量或终态推断诊断成功、远端合同结果或BUSINESS完成。

原Task为`072bc074-4bce-4c14-b425-f2ff965c7d40`，execution为`45e3a9a4-a6b8-403a-89f5-605e11eeeca0`，Runtime为`agent-live-file-transfer-8pqtfa76o`。本次不新建部署，不重发原272 UNKNOWN回滚或385接续，不重装READY266。入口是一次真实产品协调：前置eligible时预期选择独立APP/fresh grant的新固定只读三项原生合同诊断；状态漂移仍受原生产守门控制，不能冒称强制pure-only。

## 前置核验与保留失败

首次capture因`EXACT_RETAINED_ROOT`退出1，未产baseline、未启动Java、未打开DB或联网。两个历史映射曾误指270/271根，已精确改回`sealed265 → file-transfer-post-preflight-ledger-265`、`sealed266 → agent-publication-current-state-continuation-266`；原失败输入、日志、闭合及更正diff保留。父另一次prepare参数误填SHA，被`PREPARE_EXACT_BINDING_SHA`在snapshot及compiler授权前拒绝，无产品副作用；不计作业务或Java失败。

修正后fresh capture0，baseline SHA256 `5cdd0c27e1dd490afc4f472c3e19d88c2cd2949d6bc0f989c8d8846b9956e7e6`。isolated javac0；public只读eligibility reader0，before/after字节核各0、stderr0。真实完整journal经生产公开codec与eligibility/binding/request重建，eligible=true、attempted=false，精确9 targets、90秒/16384字节；离线重建不提供远端授权或保证产品action。reader仅原锁内只读原Task，无Runtime/recovery/network；文件对锁内及关闭后不变。正常close核7个精确身份全退，独立audit含closer共8个全退，自有empty-sourcepath已删。

- reader安全stdout SHA256：`6821867867963b87fe57968c53b208d46f83c206ad40a12be1c1b556d878cbbd`。
- offline实际0，SHA256：`c15ad758d86efe1e655a0397e3ea16e62268974aa31153d8dafc519d28cd571d`。
- 3337源、62deployment/3AI/4platform/5完整host、54CP/3294文件及六JAR沿271字节链。source manifest为`23b159357f334d3f91b0fd6e7050092bacc2d4729b3ffee155c3605cc94f4eee`，deployment4081008B/`16dd5834e657e382161af2c7138286a67e17a32ae121b7c1579fd61835c98b49`。这是字节链，不是JVM CodeSource或远端效果证明。

## 实际单次协调

唯一入口`DeploymentStaticLiveAcceptanceTest#reconcilesOriginalTaskThroughProduct`，originalTaskOnly=true、configureRecoveryModel=false，保留原ENDED断言。launch记录的`baselineSha256`为`7e09140616d790d2fafb7904dee14f19a8f0eeb3e8ce1d10ffd4ebb910c425e0`。

实际窗口2026-10-17T02:56:36.3611585Z—02:58:50.8728586Z：Maven1、before/after source各0、sampler Completed、无sampler cleanup failures、无自动retry。JUnit1F/0E/0skip，expected ENDED、actual UNKNOWN；progress为`AI_RECOVERY_ORIGINAL_HOST_VERIFIED`，两个source-snapshot FILE blocker及Business result explicit reconciliation仍在。报告还保留`Original Mod recovery still owns resources`关闭异常，不能称资源或业务已结清。

| 证据（相对于本轮证据根） | SHA256 |
| --- | --- |
| `tools/original238-coordinate-272-01/result.json` | `dfc0235553bac34634c54f9f702f95c863f664b0c870036a513cf09034efe776` |
| `tools/original238-coordinate-272-01/maven.stdout.log`（222086B） | `883daf42c175aa426a102f55301b744d920e426077ae264ba5656d907bf73e3c` |
| `tools/original238-coordinate-272-01/after-metadata.json` | `87d4050ade810fbff695580e4ec280ac0efa95661a47bcea9f9431e65a020adb` |
| `tools/original238-coordinate-272-01/closure.json` | `7827c3ab6e98b8857646c192340d1cc6c6d69f75416385a0578bba4247cdb2ab` |

前后source/JAR/CP保持271字节。原DB后态226136064B/SHA256 `a89d082073aac102fed0b026d220c9060fed993f21a15725430f0a2c04c4d120`；bootstrap358B/`81c546baf9dc3e7632155d86c8ae6d657109f82f1608e01006ca97ec07f7bd8b`不变。DB变化不证明具体动作发生。result的`runtimeAndProviderEffects`是入口范围说明，不能当作已执行OBSERVED_ONLY、cleanup、FAILED结清或归档的证据。

## 用量与正常收尾

summary CLI及独立原行/身份/双时间窗/CRLF SHA/算术audit实际均0，记录于`usage/parent-actual-audit272.json`。98条原诊断中26条为本272的APP generation，72条历史排除：256为31、258为6、261为5、264为7、267为12、269为11；当前重复0，不改旧summary。

| 本窗口已知字段 | 数值 |
| --- | ---: |
| APP输入 | 818301 |
| APP输出 | 2759 |
| APP输入＋输出 | 821060 |
| 缓存命中输入 | 439296 |
| 缓存未命中输入 | 379005 |
| 输入加权缓存命中率 | 53.68391337661814% |
| APP占本窗口已知完整IO | 100% |

summary SHA256 `1eed0811aad4b66e718129ace1148bc9b736cdaf0782ba0b0385103a660749ad`。没有DEP generation样本，不将未知token补0；26条reasoning字段未知，reasoning属于output子集、不另加。callback覆盖、完整generation/HTTP/能力probe/账单仍UNKNOWN。generation不是动作数或批准数；100%不是审批通过率或FULL审批占比。本协调窗口独列，新增FULL/BUSINESS通过均0。

正常product close实际0，16个已记录精确PID/创建时间全退，两个自有TMP/UDS目录已删。main finalizer核29个身份全退；独立普通非管理员audit含finalizer核30个已记录身份全退，全部执行位false，无终止、无原数据删除。须同时保留证据限制：product closer自身未保存PID/创建时间，只留actual exit0，不能重建其精确身份或声称所有辅助进程均已逐对索引。见`process-evidence-limitation272.json`，SHA256 `8c9fca7f0b221d8b35d4d36f2d4ceb6883e156339225b4098d831a9d607eba0b`。

下一步仅273本地只读核新journal及公开codec，不盲重试协调；在此之前不推断新APP/dispatch/receipt。File Transfer后处理CSV Inspector新轮，两项完整BUSINESS v2、真实浏览器及必要收尾完成后暂停，分别报告FULL审批IO占比和加权缓存命中率，无第三项目。194/234不恢复，221不自动恢复；272未增加完整业务通过。

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-native-contract-diagnostic-272`。本记录只整理已有事实，未重新执行产品、核账CLI或进程检查；正式提交与封存由父后置完成。
