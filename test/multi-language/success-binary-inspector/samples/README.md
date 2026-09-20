# 固定样本

v2 normal.bin 为 2 块、5 条记录：3 条测量（4、-2、5）与 2 条事件（启动、完成）；sum=7、min=-2、max=5、flagsOr=3。empty.bin 为 0 条。truncated.bin 在尾 CRC 截断，corrupt.bin 的第一块 payload 被破坏，两者退出 2。

`python samples/generate.py` 确定性重建四个固定素材。`python samples/generate.py --output-dir "临时 数据" --records 100000` 生成规模数据，不提交生成的大文件。

批量失败仍输出逐文件状态和成功部分汇总，整体退出非零。0 成功、2 格式/输入错误、3 文件错误、4 子程序/协议/超时错误。详细小端字段、边界和 CRC 范围见项目 README。
