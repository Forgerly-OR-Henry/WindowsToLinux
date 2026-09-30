package gold.debug.windowstolinux.appapi.ui;

import gold.debug.windowstolinux.appapi.task.ApiException;

/**
 * Owned page mounting without access to host implementation types. / 不暴露宿主实现类型的所属页面挂载服务。
 */
public interface UiService {

    /**
     * Registers an owned contribution; visibility follows successful startup. / 登记所属扩展；仅在启动成功后对外可见。
     * @param spec registration or launch specification / 登记或启动规格
     * @param factory factory for a fresh page instance on each opening / 每次打开页面时创建新实例的工厂
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    PageRegistration register(PageSpec spec, PageFactory factory) throws ApiException;

    /** Registers an isolated fixed-data preview under the original owner lifetime; only a CLASS host can open it. / 在原所有者寿命下登记隔离固定数据预览；仅CLASS宿主可打开。
     * @param spec exact local preview identity and label / 精确本地预览身份及标签
     * @param factory fresh pure view factory without production task or service capture / 不捕获生产任务或服务的新纯视图工厂
     * @return original owner registration / 原所有者登记
     * @throws ApiException if the identity or lifetime is unavailable / 身份或寿命不可用时 */
    PageRegistration registerPreview(PageSpec spec, PageFactory factory) throws ApiException;

    /**
     * Opens a registered local page on the EDT without adding a navigation pin. / 在事件分发线程打开已注册本地页，不自动固定导航。
     * @param localPageId page identifier local to the registering Mod / 登记插件内部的页面标识
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void open(String localPageId) throws ApiException;

    /**
     * Returns the current language, theme and lock state. / 返回当前语言、主题和锁定状态。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    UiEnvironment environment();
}
