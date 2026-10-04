package gold.debug.windowstolinux.appapi.ui.page;

/**
 * One root workspace identity, or a settings/preview contribution outside main navigation. / 唯一根工作区身份，或主导航之外的设置/预览扩展。
 * @param id stable identity within the owning capability / 所属能力内的稳定身份
 * @param titleKey localization key of the page title / 页面标题的本地化键
 * @param defaultTitle title used when the message key has no translation / 消息键无翻译时使用的标题
 * @param settingsPage true for Mod settings, false for the owner's unique root workspace / true 为插件设置，false 为所属者唯一根工作区
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
