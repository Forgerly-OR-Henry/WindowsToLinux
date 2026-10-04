package gold.debug.windowstolinux.appapi.ui.page;

import javax.swing.JComponent;

/**
 * Author-owned Swing view created and released on the EDT. / 在事件分发线程创建和释放的作者 Swing 视图。
 */
public interface ModPage extends AutoCloseable {

    /**
     * Creates the owned value within the current lifecycle boundary. / 在当前生命周期边界内创建所属值。
     * @param environment environment supplied to the owned component / 提供给所属组件的环境
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws Exception if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    JComponent create(UiEnvironment environment) throws Exception;

    /**
     * Applies host environment changes on the EDT. / 在事件分发线程应用宿主环境变化。
     * @param environment environment supplied to the owned component / 提供给所属组件的环境
     */
    void environmentChanged(UiEnvironment environment);

    /** Returns only a stable internal navigation identity on the EDT, never drafts or secrets. / 在 EDT 仅返回稳定内部导航身份，不返回草稿或秘密。
     * @return empty for a root without internal restoration, otherwise a lowercase literal up to 128 characters / 无内部恢复时为空，否则为最多 128 字符的小写原样标识 */
    default String navigationId() {
        return "";
    }

    /** Restores internal navigation on the EDT before first creation or for an explicit startup target; unknown identities keep the default. / 在 EDT 首次创建前或明确启动恢复时恢复内部导航，未知身份保持默认。
     * @param id stable internal identity without drafts, paths or secrets / 不含草稿、路径或秘密的稳定内部身份 */
    default void restoreNavigation(String id) {
    }

    /**
     * Releases the owned resource; failure keeps occupancy and permits retry. / 释放所属资源；失败时保留占用并允许重试。
     * @throws Exception if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void close() throws Exception;
}
