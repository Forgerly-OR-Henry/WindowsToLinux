package gold.debug.windowstolinux.mod.deployment.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;

/** Rejects explicit catastrophic targets before model review or candidate preparation. / 在模型审核或候选准备前拒绝明确的灾难性目标。 */
public final class DeploymentCommandStaticGate {
    /** Conservative shell token boundary, including absolute executable paths. / 保守Shell词边界，包含可执行文件绝对路径。 */
    private static final String PREFIX = "(?:^|[\\s;|&()])(?:[^\\s;|&()]*/)?";

    /** Commands that cannot belong to a protected project build. / 不应属于受保护项目构建的命令。 */
    private static final Pattern SYSTEM = Pattern.compile(PREFIX
        + "(?:sudo|su|mkfs(?:\\.[a-z0-9]+)?|wipefs|fdisk|sfdisk|parted|dd|shutdown|reboot|poweroff|halt)(?=$|[\\s;|&()])");

    /** Explicit physical disk targets, excluding bounded task images and the null device. / 明确物理磁盘目标，不包含受限任务镜像及空设备。 */
    private static final String DEVICE = "/dev/(?:sd[a-z]\\d*|nvme\\d+n\\d+(?:p\\d+)?|vd[a-z]\\d*|hd[a-z]\\d*|mmcblk\\d+(?:p\\d+)?|mapper/[^\\s;|&()]+|disk/[^\\s;|&()]+)(?=$|[\\s;|&()])";

    /** Fixed scripts are denied for explicit physical disk destruction and host shutdown, rather than tool names used as data. / 固定脚本按明确物理磁盘破坏及宿主关机拒绝，不按数据中的工具名拒绝。 */
    private static final Pattern CATASTROPHIC = Pattern.compile(PREFIX
        + "(?:(?:shutdown|reboot|poweroff|halt)(?=$|[\\s;|&()])"
        + "|(?:mkfs(?:\\.[a-z0-9]+)?|wipefs|fdisk|sfdisk|parted)\\s+[^\\n;|&()]*" + DEVICE
        + "|dd\\s+[^\\n;|&()]*of\\s*=\\s*" + DEVICE + ")");

    /** One rm argument segment, stopping at shell command separators. / 单个rm参数段，在Shell命令分隔符处停止。 */
    private static final Pattern REMOVE = Pattern.compile(PREFIX + "rm\\s+([^\\n;|&()]*)");

    /** Critical directory roots must never be removed or broadly emptied. / 不得删除或大范围清空的系统关键目录根。 */
    private static final Set<String> PROTECTED = Set.of("/", "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64",
        "/opt", "/proc", "/root", "/run", "/sbin", "/srv", "/sys", "/usr", "/var", "/usr/bin", "/usr/sbin",
        "/usr/lib", "/usr/lib64", "/usr/local", "/var/lib", "/var/log", "/var/opt", "/var/spool");

    /** Explicit directory changes are checked when followed by broad relative removal. / 后续存在大范围相对删除时检查明确目录切换。 */
    private static final Pattern DIRECTORY = Pattern.compile(PREFIX + "(?:cd|pushd)\\s+(?:--\\s+)?([^\\s;|&()]+)");

    /** Explicit device overwrite and the common self-replicating shell function. / 明确设备覆盖与常见自我复制Shell函数。 */
    private static final Pattern DESTRUCTIVE = Pattern.compile(
        "(?:>{1,2}\\s*" + DEVICE + "|:\\s*\\(\\s*\\)\\s*\\{[\\s\\S]*?:\\s*\\|\\s*:)");

    /** Prevents construction. / 禁止构造。 */
    private DeploymentCommandStaticGate() {
    }

    /** Rejects known dangerous free command forms; successful checks still require independent AI and task scope validation. / 拒绝已知危险自由命令形式；通过后仍需独立AI及任务范围校验。
     * @param script complete untrusted free script / 完整不可信自由脚本
     * @throws SecurityException if an explicit static rule denies the script / 明确静态规则拒绝脚本时 */
    public static void requireAllowed(String script) {
        require(script, 24000, false);
    }

    /** Checks complete fixed executable input with the same catastrophic-target rules and its actual wire bound. / 按相同灾难性目标规则及实际传输上限检查完整固定可执行输入。
     * @param script complete actual fixed script / 完整实际固定脚本
     * @throws SecurityException if shape or explicit dangerous targets are denied / 形状或明确危险目标被拒绝时 */
    public static void requireFixedAllowed(String script) {
        require(script, 17 * 1024 * 1024, true);
    }

    /** Checks catastrophic targets and appropriate command restrictions without confusing installer sizes with free-command budgets. / 检查灾难性目标及对应命令限制，不把安装器大小混作自由命令额度。
     * @param script complete executable input / 完整可执行输入
     * @param limit actual supported input bound / 实际支持输入上限
     * @param fixed typed fixed prerequisites rather than free project commands / 类型化固定前置步骤而非自由项目命令
     * @throws SecurityException if explicit static rules reject / 明确静态规则拒绝时 */
    private static void require(String script, int limit, boolean fixed) {
        if (script == null || script.isBlank() || script.length() > limit || script.indexOf('\0') >= 0)
            throw new SecurityException("DEPLOYMENT_COMMAND_STATIC_INVALID");
        // Conservatively expose quoted or escaped command spellings; this is not a full shell parser. / 保守显露引号或转义命令拼写；这不是完整Shell解析器。
        String visible = script.replace("\\\r\n", "").replace("\\\n", "").replace("\\", "")
            .replace("'", "").replace("\"", "");
        if ((fixed ? CATASTROPHIC : SYSTEM).matcher(visible).find() || DESTRUCTIVE.matcher(visible).find())
            throw new SecurityException("DEPLOYMENT_COMMAND_STATIC_DENIED");
        var removes = REMOVE.matcher(visible);
        while (removes.find()) {
            boolean recursive = false, options = true;
            var targets = new ArrayList<String>();
            for (String argument : removes.group(1).split("\\s+")) {
                if (argument.isBlank()) continue;
                if (options && argument.equals("--")) {
                    options = false;
                    continue;
                }
                if (options && argument.equals("--recursive")) recursive = true;
                else if (options && argument.startsWith("-") && !argument.startsWith("--")) {
                    recursive |= argument.indexOf('r') >= 0 || argument.indexOf('R') >= 0;
                } else if (!options || !argument.startsWith("-")) targets.add(argument);
            }
            if (recursive && targets.stream().anyMatch(DeploymentCommandStaticGate::protectedTarget))
                throw new SecurityException("DEPLOYMENT_COMMAND_STATIC_DENIED");
            if (recursive) {
                String directory = explicitDirectory(visible.substring(0, removes.start()));
                if (directory != null && targets.stream().filter(value -> !value.startsWith("/"))
                    .anyMatch(value -> protectedTarget(directory + "/" + value)))
                    throw new SecurityException("DEPLOYMENT_COMMAND_STATIC_DENIED");
            }
        }
    }

    /** Recognizes literal critical paths and broad glob targets without executing shell expansion. / 不执行Shell展开地识别字面关键路径及大范围通配目标。
     * @param target visible removal operand / 可见删除操作数
     * @return whether the operand removes a critical root or its contents / 操作数是否删除关键目录根或其内容 */
    private static boolean protectedTarget(String target) {
        if (!target.startsWith("/")) return false;
        String normalized = normalize(target);
        if (PROTECTED.contains(normalized)) return true;
        String[] parts = normalized.substring(1).split("/");
        if (parts[0].matches(".*[\\*?\\[\\{].*")) return true;
        String last = parts[parts.length - 1];
        return last.matches(".*[\\*?\\[\\{].*")
            && PROTECTED.contains(normalized.substring(0, normalized.lastIndexOf('/')));
    }

    /** Normalizes literal POSIX dot segments without consulting the Windows filesystem. / 不访问Windows文件系统地规范字面POSIX点路径段。
     * @param path absolute visible path / 绝对可见路径
     * @return normalized absolute POSIX spelling / 已规范绝对POSIX拼写 */
    private static String normalize(String path) {
        var parts = new ArrayDeque<String>();
        for (String part : path.split("/+")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
            } else parts.addLast(part);
        }
        return "/" + String.join("/", parts);
    }

    /** Resolves only explicit literal directory changes preceding the removal. / 仅解析删除前明确的字面目录切换。
     * @param prefix visible preceding command text / 可见前置命令文本
     * @return known absolute directory or null when its spelling is dynamic / 已知绝对目录，拼写动态时为null */
    private static String explicitDirectory(String prefix) {
        String current = null;
        var directories = DIRECTORY.matcher(prefix);
        while (directories.find()) {
            String next = directories.group(1);
            if (!next.matches("[A-Za-z0-9._/-]+")) current = null;
            else if (next.startsWith("/")) current = normalize(next);
            else if (current != null) current = normalize(current + "/" + next);
        }
        return current;
    }
}
