package gold.debug.windowstolinux.api.ui;

/**
 * Lazy factory creating a fresh view when a page is reopened. / 页面重新打开时创建新视图的惰性工厂。
 */
public interface PageFactory {

    /**
     * Creates the owned value within the current lifecycle boundary. / 在当前生命周期边界内创建所属值。
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws Exception if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    ModPage create() throws Exception;
}
