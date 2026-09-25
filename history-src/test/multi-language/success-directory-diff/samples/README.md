# 固定样本

baseline 有 3 个文件。复制到临时目录，修改“说明 文档.txt”、删除 remove.txt、新增 added.txt 后：added=[added.txt]、removed=[remove.txt]、changed=[说明 文档.txt]、unchanged=1。不修改正式样本。两个快照在新进程仍可查询。退出码 0 成功、2 输入错误、3 文件/SQLite 错误、4 子程序失败。失败扫描保留 failed/interrupted 诊断记录，不能参与正式比较。

运行时请将需修改的样本复制到临时目录；预期结果同时由仓库测试运行器核对。

`python samples/generate.py "临时 文件树" --files 10000` 生成空文件、重复内容和多层目录；输出目录必须为空。标准验收额外添加深层空文件，共 10001 个目标文件，并使用 Windows 目录联接检查“不跟随链接”。
