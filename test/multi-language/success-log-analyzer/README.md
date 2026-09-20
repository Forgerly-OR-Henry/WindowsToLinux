# 多源日志分析工具

Rust 发现文件、流式解压 gzip、限制并发和输出、合并报告；C++ 逐行解析文本/JSON Lines 并筛选聚合。重复输入路径及目录重叠会去重，单个文件失败不会掩盖其他成功文件，汇总仅包含完整成功项。

## 独立构建

需要 Rust/Cargo（本地 1.95 GNU）、CMake >=3.21、Ninja 和 C++20 编译器。`Cargo.lock` 固定 serde_json 与 flate2；[flate2 MultiGzDecoder](https://docs.rs/flate2/1.1.10/flate2/read/struct.MultiGzDecoder.html) 处理拼接 gzip 成员。nlohmann/json 3.12.0 源码及许可独立随项目提供。

在 `native`：

```powershell
cmake --preset w2l-release
cmake --build --preset w2l-release-build
```

在 `cli`：`cargo build --locked --release`。以下在项目根目录运行，Linux 删除 `.exe` 并使用 `./`：

```powershell
.\cli\target\release\log-analyzer.exe --input samples/events.log --format json
python samples/generate.py "运行 日志" --records 100000
.\cli\target\release\log-analyzer.exe --input "运行 日志" --jobs 3 --output report.json
.\cli\target\release\log-analyzer.exe --input "运行 日志" --service api --level ERROR --from 2026-09-19T10:10:00Z --to 2026-09-19T10:20:00Z --keyword timeout --top 5 --format json
```

## 操作与预期

1. 固定日志有 7 行，6 行匹配、1 行无法解析；INFO=2、WARN=1、ERROR=3、DEBUG=0。错误组 `api: connection timeout` 为 2；按 `ERROR`、`--from 2026-09-19T10:01:00Z` 应匹配 2 行。
2. 生成三种输入并分析目录，汇总应有 100000 行。调换显式 `--input` 顺序应得到相同汇总；同一路径重复指定不重复统计。
3. 加入一个损坏的 `.gz` 再运行，整体退出非零，JSON `status=partial`；成功文件仍保留完整统计和逐项状态。修复文件后重跑应恢复成功。`--max-keys 1` 会明确报告资源限制。

文本格式为 `UTC时间 LEVEL [service] message`，JSONL 为 `{time,level,service,message}`。UTC 格式严格为有效 `YYYY-MM-DDTHH:mm:ssZ`；接受 DEBUG/INFO/WARN/ERROR，并把 WARNING/ERR 规范为 WARN/ERROR。过滤条件取交集。错误归类按服务及将连续十进制数字替换为 `#` 后的消息；不会把其他文字或标点合并。分钟桶、服务、级别及完整错误分组共同用于跨文件汇总，最后才计算 top 排名。无法解析的行计数并保存每文件前 20 条位置/原因。

## 配置与边界

`--jobs` 默认 2，范围 1..16；`--timeout-ms` 默认 30000，最高 300000；`--max-bytes` 默认 256 MiB，最高 1 GiB，对原始/解压后的文件都生效；`--max-keys` 默认 10000，最高 100000，限制分钟/服务/错误组的总键数；`--top` 默认 10，范围 1..100。目录深度最多 128，输入文件最多约 10000；符号链接和 Windows 重解析点明确报告为不处理项。

`NATIVE_HELPER` 可指定配套程序完整路径，默认相对 CLI 可执行文件定位 `native/build/log-worker[.exe]`，无需固定工作目录。gzip 使用 `DATA_DIR` 或系统临时目录，每次解压有大小限制，正常、损坏和超限路径均删除本次临时文件。

每行限制 64 KiB、消息 8192 字节，单子程序输出 8 MiB，整个报告 16 MiB。超过限制明确失败；不会将截断结果标为成功。主程序以参数数组启动 C++，超时 kill 后 wait，stdout/stderr 有界读取。

子程序 NDJSON 协议为版本 2：`start(sequence=0)`、`summary(sequence=1)`、`end(sequence=2,messages=3,records=总行数)`，均带 `component=cpp-log`。Rust 核对消息顺序、字段及级别/服务/分钟/错误组计数。诊断写 stderr。退出码：0 成功、2 参数错误、3 输入文件错误、4 子程序/协议/资源汇总失败；批量取最大错误码。

Windows 标准验收使用 100000 条独立生成记录，覆盖实际 gzip、格式等价、筛选、顺序、部分失败、超限和子程序故障。实际记录见上级 `VERIFICATION.md`；Linux 实机和产品部署待验证。生成文件、临时解压和报告不作为源码提交。
