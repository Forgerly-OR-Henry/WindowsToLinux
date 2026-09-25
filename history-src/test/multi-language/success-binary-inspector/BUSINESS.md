# 分块二进制检查：业务与验收规范

版本：2。直接升级，无旧数据迁移；固定身份只用于演示。

## 数据与职责

Rust batch/filter/report；C header/block/record parser

## 状态流

逐文件 complete/error；错误报告文件、块、偏移；批量继续但总退出非零

## 接口

WTL2 小端：24字节头 magic/version/headerSize/blockCount/recordCount/reserved；每块16字节头 magic/payloadBytes/recordCount/crc32；尾 CRC32；记录 kind:u8 flags:u8 payloadLen:u16 + payload。测量 payload id:u32,value:i64；事件 payload id:u32,UTF-8 bytes

## 不变量

版本2；每块≤1MiB，事件≤4096字节，文件默认≤256MiB。C 流式计算块和整文件 CRC，未知类型拒绝；--type measurement|event、--min、--max 过滤测量值，校验仍覆盖所有记录。

## 标准验收

100,000 两类记录多块；独立 Python 格式生成/预期；头/长度/计数/CRC/未知类型破坏；坏文件不掩盖其他项；子程序故障

每项包含正常、业务拒绝、异常恢复三类流程；先按此规范实现，再更新 README 与实际验证记录。旧版本通过记录不代表本版通过。
