package gold.debug.windowstolinux.appapi.file.material;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;

/** Small immutable identity of original task-owned material; possession alone grants no access. / 原任务素材的小型不可变身份，持有值本身不授予访问权。
 * @param id original material identity / 原素材身份
 * @param operationId original producer operation / 原生产操作
 * @param taskId authentic original root task / 真实原根任务
 * @param producerId original producer Mod / 原生产方 Mod
 * @param producerVersion original producer release / 原生产方发布
 * @param producerProgramSha256 original complete program digest / 原完整程序摘要
 * @param producerSchema original producer data generation / 原生产方数据代次
 * @param revision immutable source revision / 不可变来源修订
 * @param manifestSha256 canonical source manifest digest / 规范来源清单摘要
 * @param byteCount exact original file bytes / 精确原文件字节数
 * @param fileCount exact original file count / 精确原文件数量 */
public record MaterialRef(UUID id, UUID operationId, UUID taskId, String producerId, String producerVersion,
                          String producerProgramSha256, Optional<SchemaIdentity> producerSchema, String revision,
                          String manifestSha256, long byteCount, int fileCount) {
    /** Rejects incomplete metadata without inferring authority. / 拒绝不完整元数据，不推导授权。
     * @param id original material identity / 原素材身份
     * @param operationId original operation / 原操作
     * @param taskId original task / 原任务
     * @param producerId original producer / 原生产方
     * @param producerVersion original release / 原发布
     * @param producerProgramSha256 complete program digest / 完整程序摘要
     * @param producerSchema original data generation / 原数据代次
     * @param revision immutable revision / 不可变修订
     * @param manifestSha256 canonical manifest digest / 规范清单摘要
     * @param byteCount exact byte count / 精确字节数
     * @param fileCount exact file count / 精确文件数 */
    public MaterialRef {
        Objects.requireNonNull(id);
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(taskId);
        Objects.requireNonNull(producerSchema);
        if (producerId == null || producerId.isBlank() || producerId.length() > 512
            || producerVersion == null || producerVersion.isBlank() || producerVersion.length() > 512
            || producerProgramSha256 == null || !producerProgramSha256.matches("[0-9a-f]{64}")
            || revision == null || revision.isBlank() || revision.length() > 4096
            || manifestSha256 == null || !manifestSha256.matches("[0-9a-f]{64}") || byteCount < 0
            || fileCount < 0 || fileCount > 1_000_000
            || producerSchema.filter(value -> !value.ownerId().equals(producerId)).isPresent())
            throw new IllegalArgumentException("Invalid original material identity");
    }
}
