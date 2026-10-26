package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input;

import java.util.ArrayList;
import java.util.List;

/** Literal argument notation for editable launch settings. / 可编辑启动设置的字面参数记法。 */
public final class DeploymentCommandText {
    private DeploymentCommandText() {
    }

    public static List<String> parse(String text) {
        if (text.length() > 12_000 || text.indexOf('\0') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Command arguments exceed bounds");
        var result = new ArrayList<String>();
        var token = new StringBuilder();
        char quote = 0;
        boolean escaped = false, started = false;
        for (char value : text.toCharArray()) {
            if (escaped) {
                token.append(value);
                escaped = false;
                started = true;
            } else if (value == '\\' && quote != '\'') {
                escaped = true;
                started = true;
            } else if (quote != 0) {
                if (value == quote) quote = 0;
                else token.append(value);
            } else if (value == '\'' || value == '"') {
                quote = value;
                started = true;
            } else if (Character.isWhitespace(value)) {
                if (started) {
                    result.add(token.toString());
                    token.setLength(0);
                    started = false;
                }
            } else {
                token.append(value);
                started = true;
            }
        }
        if (quote != 0 || escaped) throw new IllegalArgumentException("Close the quote or complete the escape in command arguments");
        if (started) result.add(token.toString());
        new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentCommand("", result);
        return List.copyOf(result);
    }

    public static String format(List<String> arguments) {
        return arguments.stream().map(value -> value.matches("[A-Za-z0-9_@%+=:,./*-]+") ? value
            : "'" + value.replace("'", "'\\''") + "'").collect(java.util.stream.Collectors.joining(" "));
    }
}
