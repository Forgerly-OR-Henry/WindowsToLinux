# 资产借还管理：业务与验收规范

版本：2。直接升级，无旧数据迁移；固定身份只用于演示。

## 数据与职责

categories / assets / loans / loan_items / maintenance / events / requests

## 状态流

借用单 draft → submitted → approved/rejected → checked_out → partially_returned → closed；资产 available → reserved → lent → available/maintenance

## 接口

GET/POST /api/categories、/api/assets、/api/loans；GET /api/loans/{id}；POST /api/loans/{id}/submit|approve|reject|checkout|return；GET /api/maintenance；POST /api/maintenance/{id}/complete；GET /api/stats

## 不变量

1..20 件资产；审批 BEGIN IMMEDIATE 原子占用整单，冲突全回滚。归还含逐项 condition，损坏生成维修工单。变更使用 requestId 幂等键并记录演示经办人。

## 标准验收

10,000 件资产；跨分类筛选与分页；10 单竞争同资产；部分归还、损坏维修闭环；逾期统计；重启与事务中断

每项包含正常、业务拒绝、异常恢复三类流程；先按此规范实现，再更新 README 与实际验证记录。旧版本通过记录不代表本版通过。
