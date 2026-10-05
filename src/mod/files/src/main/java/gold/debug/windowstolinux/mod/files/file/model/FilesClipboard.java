package gold.debug.windowstolinux.mod.files.file.model;

import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.appapi.file.model.FileInfo;

/** Workspace-local intent and observations; preparation changes no file. / 工作区内部意图及观测；准备不修改文件。
 * @param mode explicit copy or move intent / 明确复制或移动意图
 * @param sources original selected objects to revalidate / 待重新核验的原选中对象 */
public record FilesClipboard(Mode mode, List<FileInfo> sources) {
    /** Copies a nonempty bounded selection. / 复制非空有界选择。
     * @param mode explicit intent / 明确意图
     * @param sources original observations / 原观测 */
    public FilesClipboard {
        Objects.requireNonNull(mode);
        sources = List.copyOf(sources);
        if (sources.isEmpty() || sources.size() > 100000)
            throw new IllegalArgumentException("A bounded nonempty selection is required");
    }

    /** Explicit operation intent. / 明确操作意图。 */
    public enum Mode {
        /** Copy through the platform. / 经平台复制。 */
        COPY,
        /** Move through the platform without early deletion. / 经平台移动，不提前删除。 */
        MOVE
    }
}
