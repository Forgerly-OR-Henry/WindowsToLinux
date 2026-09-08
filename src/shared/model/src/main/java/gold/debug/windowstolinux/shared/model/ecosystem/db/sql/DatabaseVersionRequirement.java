package gold.debug.windowstolinux.shared.model.ecosystem.db.sql;

import java.util.*;
import java.util.regex.Pattern;

/** Explicit database release constraints; a newer version is compatible with a minimum requirement. */
public final class DatabaseVersionRequirement {
    private static final Pattern TERM = Pattern.compile("(>=|<=|>|<|=)?([0-9]{1,3}(?:\\.[0-9]{1,3}){0,2})(?:\\.x)?");
    private final String declaration;
    public DatabaseVersionRequirement(String declaration) {
        this.declaration = Objects.requireNonNull(declaration).trim();
        if (this.declaration.length() > 100) throw new IllegalArgumentException("database version requirement is too long");
        for (String term : terms()) if (!TERM.matcher(term).matches()) throw new IllegalArgumentException("unsupported database version constraint");
    }
    public String declaration() { return declaration; }
    public boolean accepts(String observed) {
        if (observed == null || !observed.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){0,3}")) return false;
        int[] actual = numbers(observed);
        for (String term : terms()) {
            var matcher = TERM.matcher(term); matcher.matches();
            int[] required = numbers(matcher.group(2));
            String operator = matcher.group(1);
            int compared = compare(actual, required);
            if (operator == null || operator.equals("=")) {
                for (int index = 0; index < required.length; index++) if ((index < actual.length ? actual[index] : 0) != required[index]) return false;
            } else if (!(switch (operator) { case ">=" -> compared >= 0; case "<=" -> compared <= 0; case ">" -> compared > 0; case "<" -> compared < 0; default -> false; })) return false;
        }
        return true;
    }
    private List<String> terms() { return declaration.isEmpty() ? List.of() : List.of(declaration.replaceAll("([<>=]+)\\s+", "$1").split("[,\\s]+")); }
    private static int[] numbers(String value) { return Arrays.stream(value.split("\\.")).mapToInt(Integer::parseInt).toArray(); }
    private static int compare(int[] left, int[] right) {
        for (int index = 0; index < Math.max(left.length, right.length); index++) {
            int compared = Integer.compare(index < left.length ? left[index] : 0, index < right.length ? right[index] : 0);
            if (compared != 0) return compared;
        }
        return 0;
    }
}
