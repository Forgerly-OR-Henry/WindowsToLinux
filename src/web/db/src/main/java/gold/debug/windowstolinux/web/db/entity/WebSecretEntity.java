package gold.debug.windowstolinux.web.db.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Carries persistent secret columns for the scoped Web repository.
 * <p>携带限定作用域 Web 仓库使用的秘密持久化列。
 *
 * @param workspaceId server-assigned workspace identifier / 服务端分配的工作区标识
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
 * @param createdBy identifier of the user who created the resource / 创建资源的用户标识
 * @param purpose purpose / 用途
 * @param ciphertext ciphertext / 密文
 * @param createdAt instant at which this record was created / 当前记录创建时刻
 */
@TableName("secrets")
public record WebSecretEntity(String workspaceId, String id, Integer version, String createdBy,
                              String purpose, byte[] ciphertext, String createdAt) { }
