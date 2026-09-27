package gold.debug.windowstolinux.app.main.mod;

import java.sql.SQLException;

import gold.debug.windowstolinux.app.db.PlatformPersistence;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.PlatformTaskRuntime;

/** Supplies existing platform capabilities to Mod assembly without depending on startup implementation. / 向 Mod 装配提供已有平台能力，不依赖启动实现。 */
public interface PlatformModHost {
    /** Returns the platform repositories for startup composition. / 返回供启动装配使用的平台仓库。
     * @return owned platform repositories / 所属平台仓库 */
    PlatformPersistence persistence();

    /** Returns the current security boundary without key material. / 返回不含密钥材料的当前安全边界。
     * @return security session owner / 安全会话所有者 */
    DesktopSecurityService security();

    /** Returns the central task executor for trusted host composition. / 返回供可信宿主装配使用的中央任务执行器。
     * @return current boot task owner / 当前启动的任务所有者 */
    PlatformTaskRuntime tasks();

    /** Exposes retained context leases to trusted lifecycle cleanup, including failed preparation. / 向可信生命周期清理公开保留上下文占用，包括失败准备。
     * @return exact original context ownership / 精确原上下文归属 */
    java.util.Map<String, PlatformModContext> modContexts();

    /** Creates one real session whose durable lifecycle must finish before platform shutdown. / 创建一个真实会话，其持久生命周期必须在平台关闭前完成。
     * @param loader actual prepared code owner / 实际已准备代码所有者
     * @param schema selected data generation / 选定数据代次
     * @param mode normal or recovery mode / 普通或恢复模式
     * @param pages actual page registry / 实际页面登记
     * @param platformApi actual host API release / 实际宿主 API 版本
     * @return retained original lifecycle session / 保留的原生命周期会话
     * @throws Exception if context preparation or duplicate ownership prevents construction / 上下文准备或重复归属阻止构造时 */
    gold.debug.windowstolinux.app.mod.lifecycle.ModRuntimeSession modSession(
            gold.debug.windowstolinux.app.mod.loader.ModClassLoader loader,
            java.util.Optional<gold.debug.windowstolinux.appapi.db.SchemaIdentity> schema,
            gold.debug.windowstolinux.appapi.mod.ModLaunchMode mode,
            gold.debug.windowstolinux.app.ui.mod.ModPageRegistry pages,
            gold.debug.windowstolinux.app.mod.version.SemanticVersion platformApi) throws Exception;

    /** Exposes original sessions for host cleanup retries without replaying author startup. / 向宿主清理重试提供原会话，不重放作者启动。
     * @return retained session identities / 保留会话身份 */
    java.util.Map<String, gold.debug.windowstolinux.app.mod.lifecycle.ModRuntimeSession> modSessions();

    /** Returns host-only Mod intent management using the actual compiled public API identity. / 使用实际编译公开 API 身份返回仅供宿主的 Mod 意图管理。
     * @return original management service / 原管理服务
     * @throws Exception if build identity or archive registration is unavailable / 构建身份或归档登记不可用时 */
    gold.debug.windowstolinux.app.service.mod.PlatformModIntents modIntents() throws Exception;

    /** Returns service graph assembly for trusted lifecycle composition. / 返回供可信生命周期装配使用的服务图。
     * @return actual service publication owner / 实际服务发布所有者 */
    gold.debug.windowstolinux.app.service.mod.PlatformModServices services();

    /** Creates an exact owner-bound public logger for one activation. / 为一次启用创建精确所有者绑定公开日志器。
     * @param owner actual Mod identity / 实际 Mod 身份
     * @param version loaded release / 加载发布版本
     * @param schema optional business generation / 可选业务代次
     * @return original activation's logger / 原启用日志器 */
    gold.debug.windowstolinux.app.service.log.PlatformLogService.Binding logs(String owner, String version,
            java.util.Optional<gold.debug.windowstolinux.appapi.db.SchemaIdentity> schema);

    /** Creates task-bound local source access for one Mod activation. / 为一次 Mod 启用创建任务绑定本地来源访问。
     * @param owner exact Mod identity / 精确 Mod 身份
     * @param version actual loaded release / 实际已加载版本
     * @return host-owned source capability / 宿主持有的来源能力 */
    gold.debug.windowstolinux.app.service.source.PlatformSourceAccess source(String owner, String version);

    /**
     * Creates an activation-bound public server reference capability. / 创建绑定一次启用的公开服务器引用能力。
     * @param owner exact Mod identity / 精确 Mod 身份
     * @param version actual loaded release / 实际已加载版本
     * @param schema selected business generation, if present / 选定业务代次，可不存在
     * @return host-owned server adapter / 宿主持有的服务器适配器
     */
    gold.debug.windowstolinux.app.service.server.PlatformServerAccess serverAccess(String owner, String version,
            java.util.Optional<gold.debug.windowstolinux.appapi.db.SchemaIdentity> schema);

    /**
     * Creates protected-byte and reference access for one Mod activation. / 为一次 Mod 启用创建受保护字节及引用访问。
     * @param owner exact Mod identity / 精确 Mod 身份
     * @param version actual loaded release / 实际已加载版本
     * @param schema selected optional business generation / 选定的可选业务代次
     * @return host-owned secret adapter / 宿主持有的秘密适配器
     */
    gold.debug.windowstolinux.app.service.secret.PlatformSecretService secrets(String owner, String version,
            java.util.Optional<gold.debug.windowstolinux.appapi.db.SchemaIdentity> schema);

    /**
     * Creates task-owned generic model access for one Mod activation. / 为一次 Mod 启用创建任务所属通用模型访问。
     * @param owner exact Mod identity / 精确 Mod 身份
     * @param version actual loaded release / 实际已加载版本
     * @return host-owned model capability / 宿主持有的模型能力
     */
    gold.debug.windowstolinux.app.service.ai.PlatformAiService ai(String owner, String version);

    /** Creates an original-owner managed-process capability without starting a child. / 创建原所有者受管进程能力，不启动子进程。
     * @param owner actual Mod identity / 实际 Mod 身份
     * @param version loaded release / 加载发布版本
     * @return bound process capability / 绑定进程能力 */
    gold.debug.windowstolinux.app.service.process.PlatformProcessService processes(String owner, String version);

    /**
     * Creates real task, credential, review and SSH adapters for one Mod activation. / 为一次 Mod 启用创建真实任务、凭据、审核及 SSH 适配。
     * @param ownerId exact activating Mod / 精确正在启用的 Mod
     * @param version exact loaded release / 精确已加载版本
     * @return host-owned capability pair / 宿主持有的能力组合
     */
    Remote remoteCapabilities(String ownerId, String version);

    /** Owns the review and transport pair prepared by the host. / 持有宿主准备的审核与传输能力组合。 */
    interface Remote extends AutoCloseable {
        /** Returns the bound review capability. / 返回绑定审核能力。
         * @return review service / 审核服务 */
        gold.debug.windowstolinux.appapi.task.InteractionService interaction();

        /** Returns the bound transport capability. / 返回绑定传输能力。
         * @return transport service / 传输服务 */
        gold.debug.windowstolinux.appapi.linux.LinuxService linux();

        /** Releases the original capabilities after actual resource cleanup. / 实际资源清理后释放原能力。
         * @throws gold.debug.windowstolinux.appapi.task.ApiException if cleanup remains / 仍有清理残留时 */
        @Override
        void close() throws gold.debug.windowstolinux.appapi.task.ApiException;
    }
}
