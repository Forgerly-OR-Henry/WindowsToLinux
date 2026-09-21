package gold.debug.windowstolinux.web.db.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Carries persistent preference columns for the scoped Web repository.
 * <p>携带限定作用域 Web 仓库使用的偏好持久化列。
 *
 * @param workspaceId server-assigned workspace identifier / 服务端分配的工作区标识
 * @param userId user id / 用户标识
 * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
 * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
 * @param updatedAt updated at / 已更新时刻
 */
@TableName("preferences")
public record WebPreferenceEntity(String workspaceId, String userId, String name, String value, String updatedAt) { }
