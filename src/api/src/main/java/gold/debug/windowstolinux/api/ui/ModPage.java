package gold.debug.windowstolinux.api.ui;

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

    /**
     * Releases the owned resource; failure keeps occupancy and permits retry. / 释放所属资源；失败时保留占用并允许重试。
     * @throws Exception if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void close() throws Exception;
}
