package gold.debug.wintolin.tools.language.tjava;

import gold.debug.wintolin.exceptionanderror.MethodUtils;
import gold.debug.wintolin.exceptionanderror.MyException;

import java.lang.reflect.Method;

/**
 * Windows 部署
 * 二期工程，暂不实现
 */
final class TJWindowsDeploy {

    private static final Method METHOD_DEPLOY =
            MethodUtils.getCurrentMethod(TJWindowsDeploy.class, "deploy");

    private TJWindowsDeploy() {
        throw new UnsupportedOperationException("TJWindowsDeploy is a utility class.");
    }

    static void deploy() {
        throw new MyException(
                TJWindowsDeploy.class,
                METHOD_DEPLOY,
                "Windows 部署功能暂未实现，该功能属于二期工程。",
                "WINDOWS_DEPLOY_NOT_IMPLEMENTED"
        );
    }
}