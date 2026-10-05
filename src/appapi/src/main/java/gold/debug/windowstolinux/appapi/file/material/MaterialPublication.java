package gold.debug.windowstolinux.appapi.file.material;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Producer-selected original IDs and plain paths passed directly to the platform. / 生产方预选原身份及直接交平台的普通路径。
 * @param materialId pre-recorded material identity / 预记素材身份
 * @param operationId pre-recorded original operation / 预记原操作
 * @param revision exact immutable revision / 精确不可变修订
 * @param relativePaths explicit original file names / 明确原文件名称 */
public record MaterialPublication(UUID materialId, UUID operationId, String revision, List<String> relativePaths) {
    /** Copies path values without granting filesystem access. / 复制路径值，不授予文件系统访问权。
     * @param materialId original material identity / 原素材身份
     * @param operationId original operation / 原操作
     * @param revision immutable revision / 不可变修订
     * @param relativePaths original relative names / 原相对名称 */
    public MaterialPublication {
        Objects.requireNonNull(materialId);
        Objects.requireNonNull(operationId);
        relativePaths = List.copyOf(relativePaths);
        if (revision == null || revision.isBlank() || revision.length() > 4096 || relativePaths.size() > 1_000_000)
            throw new IllegalArgumentException("Invalid material publication");
    }
}
