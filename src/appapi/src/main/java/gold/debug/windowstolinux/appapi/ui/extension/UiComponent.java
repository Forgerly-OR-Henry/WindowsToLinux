package gold.debug.windowstolinux.appapi.ui.extension;

import java.util.Optional;
import javax.swing.JComponent;

import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;

/** Fresh author component for one mount, with EDT callbacks. / 单次挂载的新作者组件，回调在 EDT 执行。
 * @param <C> detached point context / 分离的位置上下文 */
public interface UiComponent<C> extends AutoCloseable {
    /** Creates an unattached component once. / 一次创建尚未挂载的组件。
     * @param context initial optional selection / 初始可选选择
     * @param environment initial environment / 初始环境
     * @return independent component / 独立组件
     * @throws Exception if creation fails / 创建失败时 */
    JComponent create(Optional<C> context, UiEnvironment environment) throws Exception;

    /** Updates the existing instance without losing drafts. / 更新现有实例，不丢失草稿。
     * @param context new optional selection / 新可选选择
     * @throws Exception if updating fails / 更新失败时 */
    void contextChanged(Optional<C> context) throws Exception;

    /** Applies appearance and lock changes to the existing instance. / 将外观及锁定变化应用到现有实例。
     * @param environment current environment / 当前环境
     * @throws Exception if notification fails / 通知失败时 */
    void environmentChanged(UiEnvironment environment) throws Exception;

    /** Releases listeners and resources; failed cleanup remains retryable. / 释放监听器及资源，失败清理可重试。
     * @throws Exception if cleanup remains incomplete / 清理未完成时 */
    @Override
    void close() throws Exception;
}
