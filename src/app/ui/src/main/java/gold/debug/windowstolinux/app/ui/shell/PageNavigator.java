package gold.debug.windowstolinux.app.ui.shell;

/** Navigates the shell without exposing its Swing components to pages. / 在不向页面公开 Swing 组件的情况下导航外壳。 */
@FunctionalInterface
interface PageNavigator {
    /** Shows a page and its localized heading. / 显示页面及其本地化标题。 */
    void show(String page, String titleKey, String descriptionKey);
}
