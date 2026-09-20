# 项目任务看板：业务与验收规范

版本：2。直接升级，无旧数据迁移；固定身份只用于演示。

## 数据与职责

projects / members / tasks / dependencies / comments / events

## 状态流

todo → doing → review → done；review 可退回 doing；完成时所有依赖必须 done

## 接口

GET /api/projects、/api/members；GET/POST /api/tasks；GET/PATCH /api/tasks/{id}；POST /api/tasks/{id}/transition、/comments；GET /api/stats?projectId

## 不变量

version 乐观锁；负责人须属于项目，依赖同项目、禁止环；数据库分页、组合查询和统计。固定演示身份非认证。

## 标准验收

10,000 任务；跨项目隔离；依赖拒绝；完整审核流；10 并发编辑只有一个版本成功；重启与事务故障

每项包含正常、业务拒绝、异常恢复三类流程；先按此规范实现，再更新 README 与实际验证记录。旧版本通过记录不代表本版通过。
