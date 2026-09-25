package gold.debug.windowstolinux.app.service.contract.definition;

/**
 * Supported typed health modes in the multi-component form. / 多组件表单支持的类型化健康模式。
 */
public enum ComponentHealthMode {
    /**
    * HTTP protocol classification within component health mode.
    * <p>组件健康模式中的HTTP 协议分类。
    */
    HTTP,
    /**
    * TCP classification within component health mode.
    * <p>组件健康模式中的TCP分类。
    */
    TCP,
    /**
     * PROCESS classification within component health mode.
     * <p>组件健康模式中的进程分类。
     */
    PROCESS,
    /**
     * COMMAND classification within component health mode.
     * <p>组件健康模式中的命令分类。
     */
    COMMAND,
    /**
     * UDP classification within component health mode.
     * <p>组件健康模式中的UDP分类。
     */
    UDP
}
