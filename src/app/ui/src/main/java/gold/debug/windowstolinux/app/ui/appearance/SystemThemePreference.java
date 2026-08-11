package gold.debug.windowstolinux.app.ui.appearance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads the Windows application appearance preference without writing to the system registry.
 *
 * <p>读取 Windows 应用外观偏好，但不写入系统注册表。
 */
public final class SystemThemePreference {
    private SystemThemePreference() {
    }

    /**
     * Performs the {@code effectiveTheme} operation.
     *
     * <p>执行 {@code effectiveTheme} 操作。
     *
     * @param preference the {@code preference} value / {@code preference} 值
     * @return the operation result / 操作结果
     */
    public static ThemeMode effectiveTheme(ThemeMode preference) {
        if (preference != ThemeMode.SYSTEM) {
            return preference;
        }
        return windowsPrefersLight().map(light -> light ? ThemeMode.LIGHT : ThemeMode.DARK).orElse(ThemeMode.LIGHT);
    }

    static Optional<Boolean> windowsPrefersLight() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return Optional.empty();
        }
        try {
            Process process = new ProcessBuilder("reg.exe", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme").redirectErrorStream(true).start();
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
