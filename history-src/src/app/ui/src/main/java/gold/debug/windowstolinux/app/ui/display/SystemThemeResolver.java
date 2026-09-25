package gold.debug.windowstolinux.app.ui.display;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads the Windows application appearance preference without writing to the system registry.
 *
 *  <p>读取 Windows 应用外观偏好，但不写入系统注册表。
 */
public final class SystemThemeResolver {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private SystemThemeResolver() {
    }

    /**
     * Uses an explicit theme unchanged, otherwise resolves the Windows preference with a light-theme fallback.
     * <p>原样使用显式主题，否则解析 Windows 偏好，并以浅色主题作为回退。
     *
     * @param preference preference / 偏好
     * @return the operation result / 操作结果
     */
    public static ThemeMode effectiveTheme(ThemeMode preference) {
        if (preference != ThemeMode.SYSTEM) {
            return preference;
        }
        return windowsPrefersLight().map(light -> light ? ThemeMode.LIGHT : ThemeMode.DARK).orElse(ThemeMode.LIGHT);
    }

    /**
     * Returns windows prefers light.
     * <p>返回Windows偏好浅色。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    static Optional<Boolean> windowsPrefersLight() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return Optional.empty();
        }
        try {
            Process process = new ProcessBuilder("reg.exe", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v",
                    "AppsUseLightTheme").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                return Optional.empty();
            }
            String normalized = output.toLowerCase(Locale.ROOT);
            if (normalized.contains("0x0")) {
                return Optional.of(false);
            }
            if (normalized.contains("0x1")) {
                return Optional.of(true);
            }
            return Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
