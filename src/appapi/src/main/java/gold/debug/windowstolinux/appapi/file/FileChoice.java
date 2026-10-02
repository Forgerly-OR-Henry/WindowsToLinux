package gold.debug.windowstolinux.appapi.file;

import java.util.Optional;

/** Separate directory and file decisions; missing decisions remain interactive. / 分开的目录/文件决定，未指定层级仍需交互。 */
public record FileChoice(Optional<Directory> directory, Optional<Conflict> file) {
    /** Validates both optional decisions. / 校验两种可选决定。 */
    public FileChoice { java.util.Objects.requireNonNull(directory); java.util.Objects.requireNonNull(file); }
    /** Directory collision choices. / 目录冲突选择。 */
    public enum Directory { COPY, MERGE, SKIP }
    /** File and link collision choices. / 文件及链接冲突选择。 */
    public enum Conflict { OVERWRITE, COPY, SKIP }
    /** Leaves both levels interactive. / 两层均采用交互。 */
    public static FileChoice ask() { return new FileChoice(Optional.empty(), Optional.empty()); }
    /** Sets both levels explicitly. / 明确指定两层策略。 */
    public static FileChoice of(Directory directory, Conflict file) { return new FileChoice(Optional.of(directory), Optional.of(file)); }
}
