package gold.debug.windowstolinux.api.ui;

/**
 * Local page identity without automatic navigation pinning. / 不会自动固定主导航的本地页面定义。
 * @param id stable identity within the owning capability / 所属能力内的稳定身份
 * @param titleKey localization key of the page title / 页面标题的本地化键
 * @param defaultTitle title used when the message key has no translation / 消息键无翻译时使用的标题
 * @param settingsPage whether this page belongs in Mod settings / 此页面是否属于插件设置
 */
public record PageSpec(String id, String titleKey, String defaultTitle, boolean settingsPage) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param id stable identity within the owning capability / 所属能力内的稳定身份
     * @param titleKey localization key of the page title / 页面标题的本地化键
     * @param defaultTitle title used when the message key has no translation / 消息键无翻译时使用的标题
     * @param settingsPage whether this page belongs in Mod settings / 此页面是否属于插件设置
     */
    public PageSpec {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(titleKey, "titleKey");
        java.util.Objects.requireNonNull(defaultTitle, "defaultTitle");
    }
}
