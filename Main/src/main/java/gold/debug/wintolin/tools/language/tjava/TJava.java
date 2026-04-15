package gold.debug.wintolin.tools.language.tjava;

import gold.debug.wintolin.attribute.language.AJava;
import gold.debug.wintolin.attribute.system.ALinux;

import java.nio.file.Path;

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
     * @param sourcePath Java 源码路径
     * @return Java 环境属性
     */
    public static AJava windowsVersionCheck(Path sourcePath) {
        return TJWindowsVersionCheck.check(sourcePath);
    }

    /**
     * Linux 版本检查（二期工程）
     *
     * @param sourcePath Java 源码路径
     * @return Java 环境属性
     */
    public static AJava linuxVersionCheck(Path sourcePath) {
        return TJLinuxVersionCheck.check(sourcePath);
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
    public static void linuxDeploy(AJava aJava, ALinux linux) {
        TJLinuxDeploy.deploy(aJava, linux);
    }
}