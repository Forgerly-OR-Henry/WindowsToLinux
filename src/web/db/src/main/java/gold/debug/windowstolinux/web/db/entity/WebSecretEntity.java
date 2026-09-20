package gold.debug.windowstolinux.web.db.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("secrets")
public record WebSecretEntity(String workspaceId, String id, Integer version, String createdBy,
                              String purpose, byte[] ciphertext, String createdAt) { }
