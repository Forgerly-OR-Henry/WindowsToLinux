package gold.debug.windowstolinux.mod.git.api;

import java.util.Objects;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.file.material.MaterialRef;

/** Small immutable fixed-source result; reading requires platform admission. / 小型不可变固定来源结果；读取要求平台准入。
 * @param operationId original operation / 原操作
 * @param commit exact immutable commit / 精确不可变提交
 * @param material platform-owned original source reference / 平台持有的原来源引用 */
public record GitSourceResult(UUID operationId, String commit, MaterialRef material) {
    /** Rejects inconsistent source identities. / 拒绝不一致来源身份。
     * @param operationId original operation / 原操作
     * @param commit immutable full SHA-1 / 不可变完整 SHA-1
     * @param material exact platform reference / 精确平台引用 */
    public GitSourceResult {
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(material);
        if (commit == null || !commit.matches("[0-9a-f]{40}") || !operationId.equals(material.operationId())
            || !commit.equals(material.revision()))
            throw new IllegalArgumentException("Git source result identities differ");
    }
}
