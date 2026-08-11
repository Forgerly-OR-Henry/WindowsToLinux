package gold.debug.windowstolinux.shared.linux.sshd.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the immutable, allowlisted phase-one privileged helper protocol script.
 *
 * <p>加载不可变、列入白名单的一期高权限辅助协议脚本。
 */
public final class PhaseOnePrivilegeHelper {
    /**
     * Exposes the {@code DIRECTORY} constant.
     *
     * <p>公开 {@code DIRECTORY} 常量。
     */
    public static final String DIRECTORY = "/usr/local/lib/windowstolinux";
    /**
     * Exposes the {@code PATH} constant.
     *
     * <p>公开 {@code PATH} 常量。
     */
    public static final String PATH = DIRECTORY + "/phase1-helper";
    private static final String RESOURCE = "/gold/debug/windowstolinux/shared/linux/sshd/protocol/phase1-helper";

    private PhaseOnePrivilegeHelper() {
    }

    /**
     * Performs the {@code renderScript} operation.
     *
     * <p>执行 {@code renderScript} 操作。
     *
     * @return the operation result / 操作结果
     */
    public static String renderScript() {
        try (InputStream stream = PhaseOnePrivilegeHelper.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("phase-one privilege helper resource is unavailable");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\\\\", "\\");
        } catch (IOException exception) {
            throw new IllegalStateException("phase-one privilege helper resource cannot be read", exception);
        }
    }
}
