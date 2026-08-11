package gold.debug.windowstolinux.shared.config.definition;

/**
 * The narrowly defined point at which a non-secret configuration value is consumed.
 *
 * <p>非秘密配置值被使用的受限时点。
 */
public enum ConfigurationScope {
    /** Runtime process configuration. / 运行进程配置。 */
    RUNTIME,
    /** Target-host build configuration. / 目标机构建配置。 */
    BUILD
}
