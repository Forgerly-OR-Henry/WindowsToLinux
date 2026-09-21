package gold.debug.windowstolinux.shared.model.lifecycle;

/**
 * A live server observation, never a value inferred from SQLite history.
 *
 *  <p>服务器实时观测结果，绝不是根据 SQLite 历史记录推断的值。
 */
public enum RuntimeState {
    /**
     * Represents the {@code RUNNING} option.
     *
     *  <p>表示 {@code RUNNING} 选项。
     */
    RUNNING,
    /**
     * INSTALLED classification within runtime state.
     * <p>运行时状态中的已安装分类。
     */
    INSTALLED,
    /**
     * Represents the {@code STOPPED} option.
     *
     *  <p>表示 {@code STOPPED} 选项。
     */
    STOPPED,
    /**
     * Represents the {@code UNKNOWN} option.
     *
     *  <p>表示 {@code UNKNOWN} 选项。
     */
    UNKNOWN,
    /**
     * Represents the {@code ERROR} option.
     *
     *  <p>表示 {@code ERROR} 选项。
     */
    ERROR
}
