package gold.debug.windowstolinux.web.db.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Carries persistent task decision columns for the scoped Web repository.
 * <p>携带限定作用域 Web 仓库使用的任务决定持久化列。
 *
 * @param workspaceId server-assigned workspace identifier / 服务端分配的工作区标识
 * @param taskId task id / 任务标识
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
 * @param promptJson prompt json / 提示JSON
 * @param answerJson answer json / 回答JSON
 * @param expiresAt expires at / 到期时刻
 * @param answeredBy answered by / 已回答执行者
 * @param answeredAt answered at / 已回答时刻
 */
@TableName("task_decisions")
public record WebTaskDecisionEntity(String workspaceId, String taskId, String id, String kind, String promptJson,
        String answerJson, String expiresAt, String answeredBy, String answeredAt) {
}
