package gold.debug.windowstolinux.app.windows.security;

import java.util.Objects;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;

/** Protects bootstrap material with Windows DPAPI for the current user. / 使用 Windows 当前用户 DPAPI 保护引导材料。 */
public final class CurrentUserProtection {
    /** Creates the Windows-only native protector without opening a database. / 创建仅限 Windows 的原生保护器，不打开数据库。 */
    public CurrentUserProtection() {
        if (!Platform.isWindows())
            throw new UnsupportedOperationException("Current-user protection requires Windows");
    }

    /**
     * Protects bytes without machine-wide access or interactive native prompts. / 保护字节，不开放整机访问或原生交互提示。
     * @param clear bytes to protect, retained by the caller / 待保护字节，仍由调用方持有
     * @return a new DPAPI-protected byte array / 新建的 DPAPI 保护字节数组
     * @throws com.sun.jna.platform.win32.Win32Exception if current-user protection fails / 当前用户保护失败时
     */
    public byte[] protect(byte[] clear) {
        return Crypt32Util.cryptProtectData(Objects.requireNonNull(clear), WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
    }

    /**
     * Verifies and decrypts material using the current Windows user profile. / 使用当前 Windows 用户配置验证并解密材料。
     * @param encrypted protected bytes, retained by the caller / 受保护字节，仍由调用方持有
     * @return clear bytes that the caller must wipe after use / 调用方使用后必须清零的明文字节
     * @throws com.sun.jna.platform.win32.Win32Exception if identity or integrity verification fails / 身份或完整性验证失败时
     */
    public byte[] unprotect(byte[] encrypted) {
        return Crypt32Util.cryptUnprotectData(Objects.requireNonNull(encrypted), WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
    }
}
