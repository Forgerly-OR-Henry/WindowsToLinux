# Original format fixtures / 原格式样本

These fixtures were produced on 2026-09-30 by freshly compiling the frozen original sources under `history-src`, outside the active Maven modules. No historical source was edited. / 这些样本在活动 Maven 模块之外，由冻结历史源码重新编译后实际生成；未修改历史源码。

The archive manifest is `e337698db89fea6e4974e9b8f5568833ae10b5685267a4ea55b1162ef7e1bd71`. The derivation compiled 52 original source files; their sorted path/raw-SHA inventory fingerprint is `30bac98e2e061f6405ad7d77b212691cddfabfa11850904b29d196b7ab62254c`. / 原归档清单及原算法源文件身份如上。

`legacy-codec-goldens.properties` contains 40 original runtime, health, activation, resource and data-path documents. Each document was read and written by its original codec and compared byte-for-byte with the migrated writer before freezing. The existing 18 codec test methods supplied the reviewed inputs and retained their corruption, truncation, trailing-data and scope rejection assertions. Three additional original documents cover the payload-validation fixture's TCP 8080/node/activation inputs. / 固定样本保留原字节格式核验及既有反例，另外涵盖载荷验证种子的输入。

`legacy-source-original.tar.gz` is the original deterministic source archive for `DeploymentSourceArchiverTest.compressedBytesMatchTheOriginalFormatAndOwnAnIndependentFrozenLifetime`: 333 compressed bytes, 71 source-content bytes, SHA-256 `c4506d54e1c4832d72d761ca8723e2bff0572715b6396698fcf9d3473874e50d`. The test independently checks the complete frozen archive bytes, tar padding, filtering, compressed limits and source-resource lifetimes. / 归档测试核对完整冻结字节和独立资源生命期。

The original configuration digest for demo/revision 2/schema v1 and `PORT=8080;BUILD:OPTIMIZE=true;LABEL=production` is `572a20bfd4263bfa7bc11b9a0ef0ce46b46710ea01439566982ce882ef2a3134`. Its test also checks ordering/time independence and application, revision and schema changes. / 配置测试另保顺序与时间无关及身份改变反例。

Local derivation commands, all 52 source SHA values, input-test SHA values and output-file SHA values are retained in `.ai-workspace/progress/phase11-evidence/s3-legacy-golden-derivation.json`, with actual output in the adjacent `.log`. Daily tests load only these fixed resources and current codecs. / 本机推导证据在上述记录；日常测试只加载固定资源和当前编解码器。
