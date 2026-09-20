# 问卷与评分管理：业务与验收规范

版本：2。直接升级，无旧数据迁移；固定身份只用于演示。

## 数据与职责

surveys / revisions / submissions / events

## 状态流

draft → published → closed；已发布内容不可修改，编辑建立新 draft 版本

## 接口

GET/POST /api/surveys；GET /api/surveys/{id}；POST /api/surveys/{id}/revisions；PUT /api/revisions/{id}；POST /api/revisions/{id}/publish|close；POST /api/submissions；GET /api/submissions、/export；Ruby POST /score

## 不变量

单选、多选、量表；visibleWhen 仅引用前置单选答案；required 仅对可见问题生效。Ruby 执行声明式选项分、权重、反向量表、维度加权并解释；历史保存不可变 revision 与评分结果。

## 标准验收

10,000 提交；10 并发同 requestId 唯一结果；隐藏必填/范围拒绝；新版规则改变 Ruby 结果且旧分数不变；下游失败不落成功记录

每项包含正常、业务拒绝、异常恢复三类流程；先按此规范实现，再更新 README 与实际验证记录。旧版本通过记录不代表本版通过。
