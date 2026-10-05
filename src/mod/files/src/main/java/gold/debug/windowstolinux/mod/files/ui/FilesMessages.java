package gold.debug.windowstolinux.mod.files.ui;

import java.util.Locale;
import java.util.ResourceBundle;

/** Resolves original workspace language without rebuilding controls. / 不重建控件地解析原工作区语言。 */
final class FilesMessages {
    /** Current immutable catalog. / 当前不可变文案目录。 */
    private ResourceBundle bundle;

    /** Loads the requested catalog. / 加载所请求文案。
     * @param locale host language / 宿主语言 */
    FilesMessages(Locale locale) {
        update(locale);
    }

    /** Switches presentation language. / 切换展示语言。
     * @param locale host language / 宿主语言 */
    void update(Locale locale) {
        bundle = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.files.ui.files", locale,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT));
    }

    /** Reads one author-owned label. / 读取一项作者所属标签。
     * @param key stable resource key / 稳定资源键
     * @return localized text / 本地化文案 */
    String text(String key) {
        return bundle.getString(key);
    }
}
