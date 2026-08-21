package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

final class DatabaseContractRules {
    private DatabaseContractRules() {
    }

    static String identifier(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }

    static String candidate(String applicationId, String value) {
        value = Objects.requireNonNull(value, "candidateId").trim();
        if (!value.matches(java.util.regex.Pattern.quote(applicationId) + "-[0-9a-f]{16}")) {
            throw new IllegalArgumentException("candidateId is not bound to the managed application");
        }
        return value;
    }

    static String name(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[A-Za-z_][A-Za-z0-9_$.-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    static String host(String value) {
        value = Objects.requireNonNull(value, "host").trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")) {
            throw new IllegalArgumentException("database host is invalid");
        }
        return value;
    }

    static String relativePath(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[A-Za-z0-9._/-]{1,255}") || value.startsWith("/")
                || value.matches("^[A-Za-z]:.*") || value.contains("\\") || value.contains("//")) {
            throw new IllegalArgumentException(name + " is not a controlled relative path");
        }
        for (String segment : value.split("/")) {
            if (segment.equals(".") || segment.equals("..") || segment.isEmpty()) {
                throw new IllegalArgumentException(name + " contains traversal");
            }
        }
        return value;
    }

    static String text(String value, String name, int maximumLength) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isEmpty() || value.length() > maximumLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must contain bounded printable text");
        }
        return value;
    }

    static List<String> evidence(List<String> values) {
        Objects.requireNonNull(values, "evidence");
        if (values.isEmpty() || values.size() > 64) throw new IllegalArgumentException("database evidence is incomplete");
        List<String> result = new ArrayList<>(values.size());
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            String text = text(value, "evidence", 512);
            if (!unique.add(text)) throw new IllegalArgumentException("database evidence contains duplicates");
            result.add(text);
        }
        return List.copyOf(result);
    }
}
