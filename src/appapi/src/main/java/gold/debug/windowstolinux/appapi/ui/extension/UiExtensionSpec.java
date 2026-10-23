package gold.debug.windowstolinux.appapi.ui.extension;

/** Stable contribution identity and position. / 稳定扩展身份及排序。
 * @param id unique local contribution identity / 所属者内唯一扩展身份
 * @param order ascending display order / 升序展示顺序 */
public record UiExtensionSpec(String id, int order) {
    /** Validates a stable identity. / 校验稳定身份。
     * @param id local identity / 本地身份
     * @param order display order / 展示顺序 */
    public UiExtensionSpec {
        if (id == null || !id.matches("[a-z][a-z0-9_.-]{0,127}"))
            throw new IllegalArgumentException("Invalid extension identity");
    }
}
