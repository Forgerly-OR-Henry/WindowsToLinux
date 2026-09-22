package gold.debug.windowstolinux.app.windows.recovery;

import java.util.List;

import gold.debug.windowstolinux.shared.model.recovery.*;

/**
 * Browser capabilities confined to one user-selected terminal. / 浏览器能力限定在用户选定的单一终端。
 */
public interface BrowserTerminalSession extends AutoCloseable {
    /**
     * Opens an isolated visible browser without observing login. / 打开隔离的可见浏览器，不观察登录过程。
     */
    void open();

    /**
     * Checks browser availability without observing login contents. / 检查浏览器可用性，不观察登录内容。
     *
     * @return true when checks browser availability without observing login contents, false otherwise / 浏览器可用性，不观察登录内容时为 true，否则为 false
     */
    boolean available();

    /**
     * Enumerates terminal elements in user-opened pages and frames. / 枚举用户打开页面及框架内的终端元素。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    List<TerminalTarget> targets();

    /**
     * Binds a user-selected terminal and returns its generation. / 绑定用户选择的终端并返回代次。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return bind as a numeric result / 绑定的数值结果
     */
    long bind(String id);

    /**
     * Reads only the bound terminal. / 仅读取已绑定终端。
     *
     * @param generation generation / 代次
     * @return only the bound terminal / 仅读取已绑定终端
     */
    TerminalObservation observe(long generation);

    /**
     * Submits one approved line, rejecting a changed binding. / 提交一条已批准的命令行，拒绝变化的绑定。
     *
     * @param generation generation / 代次
     * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
     */
    void submit(long generation, String command);

    /**
     * Checks the binding without reading page contents. / 检查绑定，不读取页面内容。
     *
     * @param generation generation / 代次
     * @return true when checks the binding without reading page contents, false otherwise / 绑定，不读取页面内容时为 true，否则为 false
     */
    boolean valid(long generation);

    /**
     * Revokes pending approvals when handing control back to the user. / 将控制交还用户时撤销待批准动作。
     */
    void invalidate();

    /**
     * Closes only this session's browser and worker. / 仅关闭此会话的浏览器及工作线程。
     */
    @Override
    void close();
}
