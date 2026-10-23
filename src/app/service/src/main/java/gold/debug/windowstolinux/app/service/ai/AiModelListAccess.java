package gold.debug.windowstolinux.app.service.ai;

import java.sql.SQLException;
import java.util.List;

import gold.debug.windowstolinux.app.service.ai.AiModelInfo;
import gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment;
import gold.debug.windowstolinux.app.ai.model.AiPurposeType;

/** Non-secret platform inventory and explicit purpose selection. / 平台非秘密模型清单及明确用途选择。 */
public interface AiModelListAccess {
    /** Reorders only the full inventory. / 仅调整完整清单顺序。
     * @param ids full ordered inventory identities / 完整有序清单标识
     * @throws SQLException if storage fails / 保存失败时
     */
    void reorderAiProviders(List<String> ids) throws SQLException;

    /** Lists all added models in display order. / 按展示顺序列出所有已添加模型。
     * @return inventory / 清单
     * @throws SQLException if reading fails / 读取失败时
     */
    List<AiModelInfo> listAiConfigurations() throws SQLException;

    /** Reads one purpose's ordered membership. / 读取一个用途的有序成员。
     * @param purpose selected purpose / 所选用途
     * @return members in invocation order / 按调用顺序排列的成员
     * @throws SQLException if reading fails / 读取失败时
     */
    List<AiPurposeAssignment> listAiPurpose(AiPurposeType purpose) throws SQLException;

    /** Atomically saves one purpose's draft. / 原子保存一个用途的草稿。
     * @param purpose selected purpose / 所选用途
     * @param assignments ordered members / 有序成员
     * @throws SQLException if validation or storage fails / 校验或保存失败时
     */
    void saveAiPurpose(AiPurposeType purpose, List<AiPurposeAssignment> assignments) throws SQLException;

    /** Checks the confirmed order before writing. / 写入前检查已确认顺序。 */
    default void reorderAiProviders(List<String> expected, List<String> requested) throws SQLException {
        if (!listAiConfigurations().stream().map(v -> v.profile().id()).toList().equals(expected))
            throw new SQLException("Inventory order changed", "40001");
        reorderAiProviders(requested);
    }

    /** Checks the confirmed membership before writing. / 写入前检查已确认成员。 */
    default void saveAiPurpose(AiPurposeType purpose, List<AiPurposeAssignment> expected,
                               List<AiPurposeAssignment> requested) throws SQLException {
        if (!listAiPurpose(purpose).equals(expected)) throw new SQLException("Purpose membership changed", "40001");
        saveAiPurpose(purpose, requested);
    }
}
