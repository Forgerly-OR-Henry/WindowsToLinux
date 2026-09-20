# 分块二进制检查工具

Rust 组织批量文件、限制并发、核验协议并汇总；C 按文件头、块头和记录逐段读取，验证全部数据后返回统计。输入含测量和 UTF-8 事件两类记录。单项失败保留其他成功项，报告明确 `partial` 并使用非零退出码。

## 独立构建与体验

需要 Rust/Cargo（本地 1.95 GNU）、CMake >=3.21、Ninja 与 C17 编译器。Cargo.lock 固定 serde_json；C 无第三方运行库。在 `native`：

```powershell
cmake --preset w2l-release
cmake --build --preset w2l-release-build
```

在 `cli`：`cargo build --locked --release`。在项目根目录：

```powershell
.\cli\target\release\binary-inspector.exe --input samples/normal.bin --format json
.\cli\target\release\binary-inspector.exe --input samples/normal.bin --type measurement --min -2 --max 4
.\cli\target\release\binary-inspector.exe --input samples/corrupt.bin --input samples/normal.bin --jobs 2 --format json --output report.json
python samples/generate.py --output-dir "规模 数据" --records 100000
.\cli\target\release\binary-inspector.exe --input "规模 数据/large.bin" --format json
```

Linux 使用 `./` 并去掉 `.exe`；Ubuntu 产品按需入口的验收范围见上级记录。CLI 默认相对自身定位 `native/build/binary-worker[.exe]`，可以从任意工作目录运行；`NATIVE_HELPER` 覆盖子程序完整路径。

normal.bin：2 块、5 条记录，其中 3 条测量和 2 条事件；sum=7、min=-2、max=5、flagsOr=3。事件为“启动”“完成”。范围 -2..4 的测量筛选应得到 2 条、sum=2。empty.bin 为 0 块/0 条。损坏和截断输入明确失败，报告保留块号（从 0 开始）及文件字节偏移。

## WTL2 格式

所有整数小端、固定宽度，无原生结构体填充。CRC-32 使用 IEEE 反射多项式 0xEDB88320，初值/终值异或均为 0xFFFFFFFF，与 Python zlib.crc32 一致。

| 部分 | 字段 |
|---|---|
| 文件头 24 字节 | magic="WTL2" 4B；version=u16(2)；headerSize=u16(24)；blockCount=u32；recordCount=u64；reserved=u32(0) |
| 块头 16 字节 | magic="BLK2" 4B；payloadBytes=u32；recordCount=u32；payloadCRC=u32 |
| 记录头 4 字节 | kind=u8（1 测量/2 事件）；flags=u8（允许 0..3）；payloadLen=u16 |
| 测量 payload 12 字节 | id=u32；value=i64 |
| 事件 payload 4..4100 字节 | id=u32；余下为 0..4096 字节严格 UTF-8 文本 |
| 文件尾 4 字节 | 整文件头、全部块头和 payload 的 CRC；不包含文件尾自身 |

每块最多 1 MiB，非空块至少 1 条；最多 65536 块和 1000000 条记录；空文件只含头与尾。数量、长度、边界、未知类型、flags、UTF-8、块 CRC、整文件 CRC 和额外尾字节全部校验。过滤不会绕过未选中记录的格式校验。C 缓冲区最大仅约 4 KiB 单记录加 5 条事件样本，不整体加载大文件。测量单文件选中值求和溢出 int64 时明确报错；Rust 批量合计使用 i128，JSON summary.sum 输出十进制字符串，避免跨文件溢出或精度丢失。

## 配置与协议

`--input` 可重复，最多 1000 项；`--jobs` 默认 2，范围 1..16；`--timeout-ms` 默认 30000，最大 300000；`--max-bytes` 默认 256 MiB，最大 1 GiB；`--type all|measurement|event`、`--min`、`--max`；`--format text|json`、`--output`。min/max 仅过滤测量记录。

子程序 stdout 为三个 NDJSON 消息，均有 protocolVersion=2、component=c-binary 和 sequence：start(0)、summary 或 error(1)、end(2,messages=3,records=已解析数量)。error 含 exitCode 和 error{message,block,offset}；文件头/文件尾错误的 block 为 null。Rust 检查类型、字段、计数、统计范围与结束标记。诊断另写 stderr。输出/诊断读取有界，超时 kill 后 wait；批量报告最大 16 MiB。

退出码：0 成功、2 格式/参数/校验错误、3 文件错误、4 子程序/协议错误；批量取最大错误码。报告中 successful 项的统计继续保留，不把失败项加入汇总。

Windows 标准验收使用 100000 条记录、98 块、75000 条测量和 25000 条事件，独立计算 CRC/汇总并注入具体偏移错误，见上级 `VERIFICATION.md`。Ubuntu 产品部署、按需入口及正常/损坏样本已经实测，范围见上级记录；标准规模 Linux 验收未执行。大数据由生成器产生，不提交。
