package gold.debug.windowstolinux.app.service.contract;

import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.contract.profile.AiProviderProfile;
import gold.debug.windowstolinux.app.service.contract.result.AiProviderSummary;
import gold.debug.windowstolinux.app.service.contract.spi.AiInventoryAccess;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiRoleInvocationResult;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiRoleContext;
import gold.debug.windowstolinux.shared.model.ai.*;

/** Desktop model inventory and purpose configuration boundary. / 桌面模型清单及用途配置边界。 */
public interface AiApplicationFacade extends AiInventoryAccess {
    /** Tests one capability and persists evidence for the exact revision. / 测试能力并保存精确修订证据。
     * @param id profile identity / 模型标识
     * @param capability selected capability / 所选能力
     * @throws Exception if testing fails / 测试失败时
     */
    void testAiCapability(String id, AiCapabilityType capability) throws Exception;

    /** Tests and saves one model without enrolling it in a purpose. / 测试并保存模型，不自动加入用途。
     * @param profile model settings / 模型设置
     * @param name display label / 显示名称
     * @param key supplied credential / 提供的凭据
     * @param capability tested capability / 已测试能力
     * @throws SQLException if storage fails / 保存失败时
     * @throws SecretStoreException if secret access fails / 秘密访问失败时
     */
    void saveAiConfiguration(AiProviderProfile profile, String name, char[] key, AiCapabilityType capability)
            throws SQLException, SecretStoreException;

    /** Requests bounded advice through the deployment purpose. / 通过部署用途请求有界建议。
     * @param context structured facts / 结构化事实
     * @return validated result or no available provider / 已校验结果或无可用模型
     * @throws SQLException if configuration access fails / 配置访问失败时
     * @throws SecretStoreException if secret access fails / 秘密访问失败时
     */
    Optional<AiRoleInvocationResult> invokeAiRole(AiRoleContext context) throws SQLException, SecretStoreException;
}
