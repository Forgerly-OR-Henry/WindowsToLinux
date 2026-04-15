package gold.debug.wintolin.tools.language.tjava;

import gold.debug.wintolin.attribute.language.AJava;
import gold.debug.wintolin.exceptionanderror.MethodUtils;
import gold.debug.wintolin.exceptionanderror.MyException;

import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * Linux 版本检查
 * 二期工程，暂不实现
 */
final class TJLinuxVersionCheck {

    private static final Method METHOD_CHECK =
            MethodUtils.getCurrentMethod(TJLinuxVersionCheck.class, "check", String.class);

    private TJLinuxVersionCheck() {
        throw new UnsupportedOperationException("TJLinuxVersionCheck is a utility class.");
    }

    static AJava check(Path sourcePath) {
        throw new MyException(
                TJLinuxVersionCheck.class,
                METHOD_CHECK,
                "Linux 版本检查暂未实现，该功能属于二期工程。",
                "LINUX_VERSION_CHECK_NOT_IMPLEMENTED"
        );
    }
}