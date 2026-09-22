package gold.debug.windowstolinux.shared.model.project.application;

import java.util.List;
import java.util.Objects;

/**
 * A reviewed program entry and argv, never shell text. / 经审阅程序入口与参数，不是 Shell 文本。
 *
 * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
 * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
 */
public record ApplicationCommand(String entrypoint, List<String> arguments) {
    /**
     * Validates and binds the inputs required by application command.
     * <p>校验并绑定应用命令所需输入。
     *
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ApplicationCommand {
        entrypoint = relative(entrypoint, true);
        arguments = List.copyOf(Objects.requireNonNull(arguments));
        if (arguments.size() > 64 || arguments.stream().anyMatch(value -> value == null || value.length() > 4096
                || value.chars().anyMatch(character -> character == 0 || character == '\n' || character == '\r')))
            throw new IllegalArgumentException("application arguments exceed their bounds");
    }

    /**
     * Validates a relative path against the enclosing resource boundary.
     * <p>按所属资源边界验证相对路径。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param emptyAllowed empty allowed / 空Allowed
     * @return relative text / 相对文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static String relative(String value, boolean emptyAllowed) {
        Objects.requireNonNull(value);
        if (value.isEmpty() && emptyAllowed)
            return value;
        if (value.length() > 512 || !value.matches("[\\p{L}\\p{N}_. /-]+") || value.startsWith("/")
                || value.contains("//")
                || java.util.Arrays.stream(value.split("/", -1)).anyMatch(part -> part.equals("..") || part.isEmpty()))
            throw new IllegalArgumentException("application path must be a literal relative path");
        return value;
    }

    /**
     * Builds application command from the supplied primary inputs.
     * <p>根据所提供主输入构建应用命令。
     *
     * @return application command from the supplied primary inputs / 根据所提供主输入构建应用命令
     */
    public static ApplicationCommand primary() {
        return new ApplicationCommand("", List.of());
    }
}
