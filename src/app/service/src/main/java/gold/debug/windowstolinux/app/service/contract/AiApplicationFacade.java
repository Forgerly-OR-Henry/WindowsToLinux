package gold.debug.windowstolinux.app.service.contract;

import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.ai.AiProviderProfile;
import gold.debug.windowstolinux.app.service.ai.AiProviderSummary;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiRoleContext;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiRoleInvocationResult;
import gold.debug.windowstolinux.shared.model.ai.*;
import java.sql.SQLException;
import java.util.*;

/** Desktop model inventory and purpose configuration boundary. / 桌面模型清单及用途配置边界。 */
public interface AiApplicationFacade {
    /** Tests one capability and persists evidence for the exact revision. / 测试能力并保存精确修订证据。
     * @param id profile identity / 模型标识
     * @param capability selected capability / 所选能力
     * @param master unlock buffer consumed by the call / 调用消耗的解锁缓冲区
     * @throws Exception if testing fails / 测试失败时
     */
    void testAiCapability(String id, AiCapabilityType capability, char[] master) throws Exception;
    /** Tests and saves one model without enrolling it in a purpose. / 测试并保存模型，不自动加入用途。
     * @param profile model settings / 模型设置
     * @param name display label / 显示名称
     * @param master unlock buffer / 解锁缓冲区
     * @param key supplied credential / 提供的凭据
     * @param capability tested capability / 已测试能力
     * @throws SQLException if storage fails / 保存失败时
     * @throws SecretStoreException if secret access fails / 秘密访问失败时
     */
    void saveAiConfiguration(AiProviderProfile profile, String name, char[] master, char[] key,
            AiCapabilityType capability) throws SQLException, SecretStoreException;
    /** Reorders only the full inventory. / 仅调整完整清单顺序。
     * @param ids full ordered inventory identities / 完整有序清单标识
     * @throws SQLException if storage fails / 保存失败时
     */
    void reorderAiProviders(List<String> ids) throws SQLException;
    /** Lists all added models in display order. / 按展示顺序列出所有已添加模型。
     * @return inventory / 清单
     * @throws SQLException if reading fails / 读取失败时
     */
    List<AiProviderSummary> listAiConfigurations() throws SQLException;
    /** Reads one purpose's ordered membership. / 读取一个用途的有序成员。
     * @param purpose selected purpose / 所选用途
     * @return members in invocation order / 按调用顺序排列的成员
     * @throws SQLException if reading fails / 读取失败时
     */
    List<AiPurposeAssignment> listAiPurpose(AiPurposeType purpose) throws SQLException;
    /** Atomically saves one purpose's draft. / 原子保存一个用途的草稿。
     * @param purpose selected purpose / 所选用途
     * @param assignments ordered members and enablement / 有序成员及启用状态
     * @throws SQLException if validation or storage fails / 校验或保存失败时
     */
    void saveAiPurpose(AiPurposeType purpose, List<AiPurposeAssignment> assignments) throws SQLException;
    /** Requests bounded advice through the deployment purpose. / 通过部署用途请求有界建议。
     * @param context structured facts / 结构化事实
     * @param masterPassword unlock buffer / 解锁缓冲区
     * @return validated result or no available provider / 已校验结果或无可用模型
     * @throws SQLException if configuration access fails / 配置访问失败时
     * @throws SecretStoreException if secret access fails / 秘密访问失败时
     */
    Optional<AiRoleInvocationResult> invokeAiRole(AiRoleContext context, char[] masterPassword)
            throws SQLException, SecretStoreException;
}
