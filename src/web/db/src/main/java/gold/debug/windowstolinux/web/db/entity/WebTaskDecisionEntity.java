package gold.debug.windowstolinux.web.db.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("task_decisions")
public record WebTaskDecisionEntity(String workspaceId, String taskId, String id, String kind,
        String promptJson, String answerJson, String expiresAt, String answeredBy, String answeredAt) { }
