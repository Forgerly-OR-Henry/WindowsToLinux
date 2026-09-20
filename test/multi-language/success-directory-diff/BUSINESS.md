# 目录快照与差异：业务与验收规范

版本：2。直接升级，无旧数据迁移；固定身份只用于演示。

## 数据与职责

C++ streamed scan；Python filters/hash pool/store/report

## 状态流

snapshot building → complete/failed/interrupted。摘要按批暂存，全部读取和两遍扫描校验成功后在单事务内发布 complete；失败保留 SQLite issues 诊断及可导出的报告，不留下伪完整快照。

## 接口

snapshot --include/--exclude/--workers；list；diff --before/--after --pattern；duplicates --snapshot；--format text|json|csv --output

## 不变量

C++ protocolVersion=2 NDJSON entry/end；Python 有界线程池摘要，检查扫描前后 size/mtime/路径；默认不跟随 symlink/reparse。同摘要增删项列出 renameCandidates，不将推断当事实。

## 标准验收

10,000 文件含深目录/空文件/中文；同大小同 mtime 内容变化；重复内容与重命名候选；扫描中文件变化/删除；中断保留旧快照

每项包含正常、业务拒绝、异常恢复三类流程；先按此规范实现，再更新 README 与实际验证记录。旧版本通过记录不代表本版通过。
