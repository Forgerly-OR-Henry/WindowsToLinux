package gold.debug.windowstolinux.appapi.linux.transfer;

/** Exact file endpoints and measured sizes shown before resolving a conflict. / 解决冲突前展示的精确文件端点及实测大小。
 * @param request original reviewed endpoints / 原已审核端点
 * @param upload whether the destination is remote / 目标是否在远端
 * @param incomingBytes verified source size / 已验证源大小
 * @param existingBytes observed existing target size / 已观察的现有目标大小 */
public record TransferConflict(TransferRequest request, boolean upload, long incomingBytes, long existingBytes) {
    /** Requires explicit endpoints and nonnegative observed sizes. / 要求明确端点及非负观测大小。
     * @param request original reviewed endpoints / 原已审核端点
     * @param upload whether the destination is remote / 目标是否在远端
     * @param incomingBytes verified source size / 已验证源大小
     * @param existingBytes observed existing target size / 已观察的现有目标大小 */
    public TransferConflict {
        java.util.Objects.requireNonNull(request);
        if (incomingBytes < 0 || existingBytes < 0)
            throw new IllegalArgumentException("Negative conflict file size");
    }
}
