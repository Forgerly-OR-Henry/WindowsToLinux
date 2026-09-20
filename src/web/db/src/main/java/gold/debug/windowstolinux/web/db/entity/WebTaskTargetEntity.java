package gold.debug.windowstolinux.web.db.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("task_targets")
public record WebTaskTargetEntity(String workspaceId, String taskId, String serverId, Integer mutating) { }
