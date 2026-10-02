package gold.debug.windowstolinux.app.windows.file;

import java.nio.file.Path;
import java.util.Objects;

/** Internal proof of an observed Windows recycle object. / 已观测 Windows 回收对象的内部证明。
 * @param path actual recycled filesystem object / 实际回收文件系统对象
 * @param identity native volume and file identifier without following a final link / 不跟随末级链接的原生卷及文件标识
 * @param information actual paired recycle metadata / 实际配对回收元数据
 * @param informationIdentity native metadata identity / 原生元数据身份
 */
public record WindowsRecycleReceipt(Path path, String identity, Path information, String informationIdentity) {
    /** Retains only an absolute path and a proven native identity. / 仅保留绝对路径及已证明的原生身份。
     * @param path actual recycled position / 实际回收位置
     * @param identity stable native identity / 稳定原生身份
     * @param information actual paired metadata position / 实际配对元数据位置
     * @param informationIdentity stable native metadata identity / 稳定原生元数据身份
     */
    public WindowsRecycleReceipt {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(information, "information");
        Objects.requireNonNull(informationIdentity, "informationIdentity");
        if (!path.isAbsolute() || !information.isAbsolute() || identity.isBlank() || informationIdentity.isBlank())
            throw new IllegalArgumentException("Invalid recycle receipt");
        path = path.normalize();
        information = information.normalize();
    }
}
