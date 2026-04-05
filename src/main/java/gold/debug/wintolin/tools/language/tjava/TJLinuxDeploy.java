package gold.debug.wintolin.tools.language.tjava;

import gold.debug.wintolin.exceptionanderror.MethodUtils;
import gold.debug.wintolin.exceptionanderror.MyException;

import java.lang.reflect.Method;

/**
 * Linux 部署
 * 二期工程，暂不实现
 */
final class TJLinuxDeploy {

    private static final Method METHOD_DEPLOY =
            MethodUtils.getCurrentMethod(TJLinuxDeploy.class, "deploy");

    private TJLinuxDeploy() {
        throw new UnsupportedOperationException("TJLinuxDeploy is a utility class.");
    }

    static void deploy() {
        throw new MyException(
                TJLinuxDeploy.class,
                METHOD_DEPLOY,
                "Linux 部署功能暂未实现，该功能属于二期工程。",
                "LINUX_DEPLOY_NOT_IMPLEMENTED"
        );
    }
}