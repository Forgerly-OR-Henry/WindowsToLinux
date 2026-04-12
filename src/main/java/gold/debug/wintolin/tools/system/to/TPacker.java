package gold.debug.wintolin.tools.system.to;

import gold.debug.wintolin.attribute.system.ALinux;
import gold.debug.wintolin.attribute.system.APackageInfo;

/**
 * 打包工具门面类
 */
public final class TPacker {
    private TPacker() {
        throw new UnsupportedOperationException("TPacker is a utility class.");
    }

    /**
     * Windows 打包
     *
     * @param aPackageInfo 包属性
     */
    public static void windowsPacker(APackageInfo aPackageInfo) {
        TPWindowsPacker.pack(aPackageInfo);
    }

    /**
     * Windows To Linux 打包传输
     *
     * @param aPackageInfo 包属性
     * @param aLinux Linux 属性
     */
    public static void packageTransfer(APackageInfo aPackageInfo, ALinux aLinux) {
        TPPackageTransfer.transfer(aPackageInfo, aLinux);
    }

    /**
     * Linux 解包
     *
     * @param aPackageInfo 包属性
     * @param aLinux Linux 属性
     */
    public static void linuxUnpacker(APackageInfo aPackageInfo, ALinux aLinux) {
        TPLinuxUnpacker.unpack(aPackageInfo, aLinux);
    }

}
