package gold.debug.windowstolinux.app.windows.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.sun.jna.platform.win32.Win32Exception;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
class CurrentUserProtectionTest {
    @Test
    void currentUserRoundTripDoesNotModifyCallerInputAndRejectsTampering() {
        var protection = new CurrentUserProtection();
        var clear = "isolated bootstrap fixture / 隔离引导夹具".getBytes(StandardCharsets.UTF_8);
        var copy = clear.clone();
        var encrypted = protection.protect(clear);
        assertArrayEquals(copy, clear);
        assertFalse(Arrays.equals(clear, encrypted));
        assertArrayEquals(clear, protection.unprotect(encrypted));
        encrypted[encrypted.length - 1] ^= 1;
        assertThrows(Win32Exception.class, () -> protection.unprotect(encrypted));
        assertThrows(Win32Exception.class, () -> protection.unprotect(new byte[]{1, 2, 3}));
        Arrays.fill(clear, (byte) 0);
        Arrays.fill(copy, (byte) 0);
    }
}
