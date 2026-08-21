package gold.debug.windowstolinux.shared.backup.manifest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

final class BackupManifestRules {
    private BackupManifestRules() {
    }

    static String requiredText(String value, String name, int maximumLength) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isEmpty() || value.length() > maximumLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must contain bounded printable text");
        }
        return value;
    }

    static String identifier(String value, String name) {
        value = requiredText(value, name, 128);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127}")) {
            throw new IllegalArgumentException(name + " is not a stable identifier");
        }
        return value;
    }

    static List<String> distinctTexts(List<String> values, String name, int maximumCount, int maximumLength) {
        Objects.requireNonNull(values, name);
        if (values.size() > maximumCount) throw new IllegalArgumentException(name + " exceeds its item bound");
        List<String> result = new ArrayList<>(values.size());
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            String normalized = requiredText(value, name, maximumLength);
            if (!unique.add(normalized.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(name + " must not contain duplicate values");
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    static String archivePath(String value) {
        value = requiredText(value, "path", 1024);
        if (value.indexOf('\\') >= 0 || value.startsWith("/") || value.endsWith("/")
                || value.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("archive member path must be relative canonical POSIX text");
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(" ") || segment.endsWith(".")) {
                throw new IllegalArgumentException("archive member path contains an unsafe segment");
            }
        }
        return value;
    }
}
