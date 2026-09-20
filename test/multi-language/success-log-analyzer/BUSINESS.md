# 多源日志分析：业务与验收规范

版本：2。直接升级，无旧数据迁移；固定身份只用于演示。

## 数据与职责

Rust input/config/worker/report；C++ parsers/aggregate

## 状态流

逐文件 processing → complete/error；其余文件继续，最终退出码取最严重错误

## 接口

--input 可重复且可为目录；--service/--level/--from/--to/--keyword/--top/--max-keys/--jobs；文本和 JSONL；Rust 解 gzip 临时流文件后参数数组调用 C++

## 不变量

protocolVersion=2，逐文件计数与有界错误样本，Rust 合并分钟/服务/错误计数。最大 key 数超限明确失败，不截断后伪成功；顺序不影响统计。

## 标准验收

100,000 混合格式记录、gzip；独立计数器预期；顺序交换；非法文件不阻止其他文件；资源上限及子程序缺失/超时/协议错误

每项包含正常、业务拒绝、异常恢复三类流程；先按此规范实现，再更新 README 与实际验证记录。旧版本通过记录不代表本版通过。
