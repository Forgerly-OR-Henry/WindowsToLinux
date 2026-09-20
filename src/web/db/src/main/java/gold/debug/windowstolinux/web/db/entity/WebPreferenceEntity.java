package gold.debug.windowstolinux.web.db.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("preferences")
public record WebPreferenceEntity(String workspaceId, String userId, String name, String value, String updatedAt) { }
