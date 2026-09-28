package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input;

import java.util.List;
import java.util.Objects;

/** One non-secret runtime answer requested from the reviewer. / 向审阅人请求的一项不含秘密的运行输入。
 * @param id component-scoped field identity / 组件限定的字段身份
 * @param titleKey localized title key / 本地化标题键
 * @param helpKey localized help key / 本地化帮助键
 * @param value source-backed suggestion or current answer / 源码依据建议或当前答案
 * @param choices fixed allowed choices, or empty for bounded text / 固定可选值，或为空表示有界文本 */
public record DeploymentReviewField(String id, String titleKey, String helpKey, String value, List<String> choices) {
    /** Copies the review descriptor before it crosses the service boundary. / 跨服务边界前复制审阅描述。
     * @param id component-scoped identity / 组件限定身份
     * @param titleKey title resource key / 标题资源键
     * @param helpKey help resource key / 帮助资源键
     * @param value non-secret suggestion / 不含秘密的建议
     * @param choices immutable options / 不可变选项 */
    public DeploymentReviewField {
        Objects.requireNonNull(id);
        Objects.requireNonNull(titleKey);
        Objects.requireNonNull(helpKey);
        Objects.requireNonNull(value);
        choices = List.copyOf(choices);
    }
}
