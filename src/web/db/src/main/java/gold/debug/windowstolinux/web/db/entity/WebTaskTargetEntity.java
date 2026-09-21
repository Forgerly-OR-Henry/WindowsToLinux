package gold.debug.windowstolinux.web.db.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Carries persistent task target columns for the scoped Web repository.
 * <p>携带限定作用域 Web 仓库使用的任务目标持久化列。
 *
 * @param workspaceId server-assigned workspace identifier / 服务端分配的工作区标识
 * @param taskId task id / 任务标识
 * @param serverId persisted server identifier / 持久化服务器标识
 * @param mutating mutating / 变更
 */
@TableName("task_targets")
public record WebTaskTargetEntity(String workspaceId, String taskId, String serverId, Integer mutating) { }
