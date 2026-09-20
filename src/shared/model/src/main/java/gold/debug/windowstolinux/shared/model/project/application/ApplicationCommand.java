package gold.debug.windowstolinux.shared.model.project.application;

import java.util.List;
import java.util.Objects;

/** A reviewed program entry and argv, never shell text. / 经审阅程序入口与参数，不是 Shell 文本。 */
public record ApplicationCommand(String entrypoint, List<String> arguments) {
    public ApplicationCommand {
        entrypoint = relative(entrypoint, true);
        arguments = List.copyOf(Objects.requireNonNull(arguments));
        if (arguments.size() > 64 || arguments.stream().anyMatch(value -> value == null || value.length() > 4096
                || value.chars().anyMatch(character -> character == 0 || character == '\n' || character == '\r')))
            throw new IllegalArgumentException("application arguments exceed their bounds");
    }

    public static String relative(String value, boolean emptyAllowed) {
        Objects.requireNonNull(value);
        if (value.isEmpty() && emptyAllowed) return value;
        if (value.length() > 512 || !value.matches("[\\p{L}\\p{N}_. /-]+") || value.startsWith("/")
                || value.contains("//") || java.util.Arrays.stream(value.split("/", -1))
                    .anyMatch(part -> part.equals("..") || part.isEmpty()))
            throw new IllegalArgumentException("application path must be a literal relative path");
        return value;
    }

    public static ApplicationCommand primary() { return new ApplicationCommand("", List.of()); }
}
