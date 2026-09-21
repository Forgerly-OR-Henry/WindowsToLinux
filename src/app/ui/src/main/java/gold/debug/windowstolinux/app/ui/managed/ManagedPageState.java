package gold.debug.windowstolinux.app.ui.managed;

import java.util.Objects;

/**
 * Represents an immutable {@code ManagedPageState} value.
 *
 *  <p>表示不可变的 {@code ManagedPageState} 值。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param output destination receiving the produced content / 接收所生成内容的目标
 * @param typeFilter type filter / 类型筛选
 * @param serverFilter server filter / 服务器筛选
 */
public record ManagedPageState(String applicationId, String output, String typeFilter, String serverFilter) {
    /**
     * Preserves earlier page snapshots. / 保留此前页面快照。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param output destination receiving the produced content / 接收所生成内容的目标
     */
    public ManagedPageState(String applicationId, String output) { this(applicationId, output, "", ""); }
    /**
     * Validates and binds the inputs required by managed page state.
     * <p>校验并绑定受管页面状态所需输入。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param typeFilter type filter / 类型筛选
     * @param serverFilter server filter / 服务器筛选
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedPageState {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(typeFilter, "typeFilter"); Objects.requireNonNull(serverFilter, "serverFilter");
    }
}
