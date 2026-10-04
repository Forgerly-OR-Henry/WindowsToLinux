package gold.debug.windowstolinux.app.file.model;

import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.file.model.FilePath;

/** Identifies the actual recoverable object and its optional separate recovery metadata. / 标识实际可恢复对象及可选独立恢复元数据。
 * @param recycled actual no-follow recycled position / 实际不跟随回收位置
 * @param identity actual recycled object identity / 实际回收对象身份
 * @param information separate recovery information when required by the backend / 后端要求时的独立恢复信息
 * @param informationIdentity exact recovery information identity / 精确恢复信息身份 */
public record FileRecycleReceipt(FilePath recycled, String identity, Optional<FilePath> information,
                                 Optional<String> informationIdentity) {
    /** Requires paired metadata evidence and a nonempty actual object identity. / 要求成对元数据依据及非空实际对象身份。
     * @param recycled actual no-follow recycled position / 实际不跟随回收位置
     * @param identity actual recycled object identity / 实际回收对象身份
     * @param information separate recovery metadata when required / 要求时的独立恢复元数据
     * @param informationIdentity exact recovery metadata identity / 精确恢复元数据身份 */
    public FileRecycleReceipt {
        Objects.requireNonNull(recycled);
        Objects.requireNonNull(identity);
        Objects.requireNonNull(information);
        Objects.requireNonNull(informationIdentity);
        if (identity.isBlank() || information.isPresent() != informationIdentity.isPresent() || informationIdentity.map(String::isBlank).orElse(false))
            throw new IllegalArgumentException("Invalid recoverable object evidence");
    }
}
