package gold.debug.windowstolinux.shared.model.lifecycle;

/**
 * Existing runtimes that can be connected without replacing their configuration. / 无需替换配置即可接入的既有运行时。
 */
public enum ExternalApplicationKind {
    /**
     * SYSTEMD classification within external application kind.
     * <p>外部应用种类中的SYSTEMD分类。
     */
    SYSTEMD,
    /**
     * DOCKER classification within external application kind.
     * <p>外部应用种类中的DOCKER分类。
     */
    DOCKER
}
