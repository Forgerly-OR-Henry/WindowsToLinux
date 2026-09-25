# 固定数据

`demo.csv`：3 条数据。基础模板的 required/number/date/range/enum/duplicate 各 1 个，共 6 个问题、1 行有效；仅必填模板为 1 个问题、2 行有效。比较基础→仅必填，应消除 5 个问题。

`generate.py` 使用确定性取模规则生成规模数据：`python generate.py output.csv --rows 100000`。不把生成的大文件提交。验收运行器另有独立生成器，增加组合重复并独立计算问题集合；100000 行应为 22937 个问题。

`people.csv` 保留为可手工自定义规则检查的另一种表头样本，不使用初始化的基础模板。
