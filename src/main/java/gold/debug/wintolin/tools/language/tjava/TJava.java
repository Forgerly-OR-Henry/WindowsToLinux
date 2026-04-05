package gold.debug.wintolin.tools.language.tjava;

import gold.debug.wintolin.attribute.language.AJava;

/**
 * Java 工具门面类
 */
public final class TJava {

    private TJava() {
        throw new UnsupportedOperationException("TJava is a utility class.");
    }

    /**
     * Windows 版本检查
     *
     * @param javaSourcePath Java 源码路径
     * @return Java 环境属性
     */
    public static AJava windowsVersionCheck(String javaSourcePath) {
        return TJWindowsVersionCheck.check(javaSourcePath);
    }

    /**
     * Linux 版本检查（二期工程）
     *
     * @param javaSourcePath Java 源码路径
     * @return Java 环境属性
     */
    public static AJava linuxVersionCheck(String javaSourcePath) {
        return TJLinuxVersionCheck.check(javaSourcePath);
    }

    /**
     * Windows 部署（二期工程）
     */
    public static void windowsDeploy() {
        TJWindowsDeploy.deploy();
    }

    /**
     * Linux 部署（二期工程）
     */
    public static void linuxDeploy() {
        TJLinuxDeploy.deploy();
    }
}