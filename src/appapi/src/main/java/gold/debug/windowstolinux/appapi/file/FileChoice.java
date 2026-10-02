package gold.debug.windowstolinux.appapi.file;

import java.util.Optional;

/** Separate directory and file decisions; missing decisions remain interactive. / 分开的目录/文件决定，未指定层级仍需交互。
 * @param directory optional collision choice for the root and recursively encountered same-name directories / 根目录及递归遇到的同名目录的可选冲突策略
 * @param file optional independent collision choice for regular files or links / 普通文件或链接的可选独立冲突策略
 */
public record FileChoice(Optional<Directory> directory, Optional<Conflict> file) {
    /** Validates both optional decisions. / 校验两种可选决定。
     * @param directory optional collision choice for the root and recursively encountered same-name directories / 根目录及递归遇到的同名目录的可选冲突策略
     * @param file optional independent collision choice for regular files or links / 普通文件或链接的可选独立冲突策略
     */
    public FileChoice {
        java.util.Objects.requireNonNull(directory);
        java.util.Objects.requireNonNull(file);
    }

    /** Directory collision choices. / 目录冲突选择。 */
    public enum Directory {
        /** Selects an actually absent counterpart name / 选择实际缺失的对应副本名称.
         */
        COPY,
        /** Merges children while preserving destination-only items / 合并子项并保留仅目标拥有的子项.
         */
        MERGE,
        /** Skips this object while independent items continue / 跳过此对象并继续独立子项.
         */
        SKIP
    }

    /** File and link collision choices. / 文件及链接冲突选择。 */
    public enum Conflict {
        /** Replaces the exact observed collision object / 替换精确观测冲突对象.
         */
        OVERWRITE,
        /** Selects an actually absent counterpart name / 选择实际缺失的对应副本名称.
         */
        COPY,
        /** Skips this object while independent items continue / 跳过此对象并继续独立子项.
         */
        SKIP
    }

    /** Leaves both levels interactive. / 两层均采用交互。
     * @return collision choices with both levels absent, requiring interaction when a collision occurs / 两层均未指定的冲突策略，出现冲突时需要交互
     */
    public static FileChoice ask() {
        return new FileChoice(Optional.empty(), Optional.empty());
    }

    /** Sets both levels explicitly. / 明确指定两层策略。
     * @param directory collision choice for the root and recursively encountered same-name directories / 根目录及递归遇到的同名目录的冲突策略
     * @param file independent collision choice for regular files or links / 普通文件或链接的独立冲突策略
     * @return immutable choices with both levels explicitly specified / 两层均明确指定的不可变策略
     */
    public static FileChoice of(Directory directory, Conflict file) {
        return new FileChoice(Optional.of(directory), Optional.of(file));
    }
}
