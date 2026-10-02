package gold.debug.windowstolinux.mod.deployment.api;

import java.util.List;

/** Exact application revision and selected binding set to review. / 待审阅的精确应用修订与所选绑定集合。
 * @param application exact source / 精确来源
 * @param expectedRevisionSha256 reviewed release / 已审阅发布
 * @param bindingIds selected binding identities / 所选绑定身份 */
public record DeploymentCollectionRequest(DeploymentApplicationRef application, String expectedRevisionSha256,
                                          List<String> bindingIds) {
    /** Copies the selected binding set. / 复制所选绑定集合。
     * @param application exact source / 精确来源
     * @param expectedRevisionSha256 reviewed release / 已审阅发布
     * @param bindingIds selected binding identities / 所选绑定身份 */
    public DeploymentCollectionRequest {
        bindingIds = List.copyOf(bindingIds);
    }
}
