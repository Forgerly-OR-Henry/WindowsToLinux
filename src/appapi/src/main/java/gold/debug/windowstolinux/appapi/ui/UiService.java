package gold.debug.windowstolinux.appapi.ui;

import gold.debug.windowstolinux.appapi.ui.page.PageFactory;
import gold.debug.windowstolinux.appapi.ui.page.PageRegistration;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.PlatformPageKind;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.appapi.ui.page.UiNavigationRequest;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionService;

import gold.debug.windowstolinux.appapi.task.result.ApiException;

/**
 * Owned page mounting without access to host implementation types. / 不暴露宿主实现类型的所属页面挂载服务。
 */
public interface UiService {

    /**
     * Registers one root workspace, or an isolated settings contribution; publication follows successful startup and adds one root navigation entry. / 登记唯一根工作区或隔离设置扩展；成功启动后发布并自动显示唯一根导航入口。
     * @param spec registration or launch specification / 登记或启动规格
     * @param factory lazy factory, reused until explicit close and then called for a fresh instance / 按需创建并复用至明确关闭，重开时创建新实例的工厂
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
     * Opens or focuses this owner's published root or settings view on the EDT; root metadata already supplies navigation. / 在事件分发线程打开或聚焦所属已发布根工作区或设置视图；根元数据已提供导航。
     * @param localPageId page identifier local to the registering Mod / 登记插件内部的页面标识
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void open(String localPageId) throws ApiException;

    /** Opens the owned page and delivers one detached transient request after creation. / 打开所属页面并在创建后交付一次分离临时请求。
     * @param localPageId exact owned page / 精确所属页面
     * @param request explicit transient navigation / 明确临时导航
     * @throws ApiException if lifetime, payload or navigation is unavailable / 寿命、载荷或导航不可用时 */
    void open(String localPageId, UiNavigationRequest<?> request) throws ApiException;

    /** Returns extensions bound to this actual activation. / 返回绑定当前真实启用的界面扩展。
     * @return owned extension capability / 所属扩展能力 */
    UiExtensionService extensions();

    /** Opens an existing fixed platform destination under this published normal owner; navigation grants no data or action authority. / 在所属已发布普通所有者下打开已有固定平台目的地；导航不授予数据或动作权限。
     * @param target fixed host destination / 固定宿主目的地
     * @throws ApiException if unpublished, locked, stopping or not composed / 未发布、锁定、停止或未装配时 */
    void openPlatform(PlatformPageKind target) throws ApiException;

    /**
     * Returns the current language, theme and lock state. / 返回当前语言、主题和锁定状态。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    UiEnvironment environment();
}
