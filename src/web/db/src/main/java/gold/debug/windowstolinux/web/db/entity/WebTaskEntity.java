package gold.debug.windowstolinux.web.db.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("tasks")
public record WebTaskEntity(String workspaceId, String id, String createdBy, String kind, String state,
        String requestJson, String resultJson, String sourceId, String applicationId, String backupId,
        String errorCode, String createdAt, String updatedAt, String finishedAt) {
    public StoredTask stored() {
        return new StoredTask(id, kind, state, requestJson, resultJson, errorCode, createdAt, updatedAt, finishedAt);
    }
}
