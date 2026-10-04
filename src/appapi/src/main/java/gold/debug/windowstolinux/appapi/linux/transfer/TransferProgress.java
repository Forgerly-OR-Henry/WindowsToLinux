package gold.debug.windowstolinux.appapi.linux.transfer;

import java.util.*;

/** Observed bytes in the current transfer or verification stage. / 当前传输或核验阶段的已观察字节数。
 * @param transferId original transfer identity / 原传输身份
 * @param stage current physical stage / 当前物理阶段
 * @param completedBytes completed bytes in this stage / 本阶段已完成字节数
 * @param totalBytes known extent, or minus one while preparing / 已知区间，准备期间未知时为负一 */
public record TransferProgress(UUID transferId, Stage stage, long completedBytes, long totalBytes) {
    /** Validates observed progress without treating an unknown extent as zero. / 校验已观察进度，不将未知范围视为零。
     * @param transferId original identity / 原身份
     * @param stage actual stage / 实际阶段
     * @param completedBytes completed stage bytes / 已完成阶段字节数
     * @param totalBytes known total or minus one / 已知总量或负一 */
    public TransferProgress {
        Objects.requireNonNull(transferId);
        Objects.requireNonNull(stage);
        if (completedBytes < 0 || totalBytes < -1 || totalBytes >= 0 && completedBytes > totalBytes)
            throw new IllegalArgumentException("Invalid transfer progress extent");
    }

    /** Returns a stage-local fraction only when its extent is known. / 仅在范围已知时返回本阶段进度比例。
     * @return optional stage fraction / 可选阶段比例 */
    public Optional<Double> fraction() {
        return totalBytes < 0
            ? Optional.empty()
            : Optional.of(totalBytes == 0 ? 1.0 : (double) completedBytes / totalBytes);
    }

    /** Distinguishes actual copying from the separate verification work. / 区分实际复制及独立核验工作。 */
    public enum Stage {
        /** Opening and checking endpoints. / 打开并检查端点。 */
        PREPARING,
        /** Reading the original source for verification. / 读取原源以核验。 */
        VERIFYING_SOURCE,
        /** Checking the selected existing target. / 核对选定已有目标。 */
        VERIFYING_TARGET,
        /** Comparing an existing staging prefix. / 比较已有暂存前缀。 */
        VERIFYING_PREFIX,
        /** Copying verified source bytes. / 复制已核验源字节。 */
        TRANSFERRING,
        /** Reading staged content before publication. / 发布前读取暂存内容。 */
        VERIFYING_STAGING,
        /** Publishing verified staging. / 发布已核验暂存。 */
        PUBLISHING,
        /** Reading the published result. / 读取已发布结果。 */
        VERIFYING_RESULT,
        /** Removing verified staging only. / 仅移除已核验暂存。 */
        CLEANING
    }
}
