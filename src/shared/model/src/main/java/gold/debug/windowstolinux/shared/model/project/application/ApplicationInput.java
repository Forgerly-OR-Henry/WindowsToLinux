package gold.debug.windowstolinux.shared.model.project.application;

import java.util.Objects;

/** External input is read-only and excluded from application backup contents. / 外部输入只读且不进入应用内容备份。 */
public record ApplicationInput(String id, String hostPath, String accessPath) {
    public ApplicationInput {
        if (!Objects.requireNonNull(id).matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("invalid input identifier");
        hostPath = absolute(hostPath); accessPath = absolute(accessPath);
        for (String forbidden : java.util.List.of("/proc", "/sys", "/dev", "/run/credentials",
                "/usr/local/lib/windowstolinux", "/var/lib/windowstolinux")) {
            if (hostPath.equals(forbidden) || hostPath.startsWith(forbidden + "/")
                    || accessPath.equals(forbidden) || accessPath.startsWith(forbidden + "/"))
                throw new IllegalArgumentException("external input overlaps runtime control resources");
        }
        for (String protectedRoot : java.util.List.of("/bin", "/sbin", "/lib", "/lib64", "/usr", "/etc",
                "/opt", "/var", "/root", "/run", "/tmp")) {
            if (accessPath.equals(protectedRoot) || accessPath.startsWith(protectedRoot + "/"))
                throw new IllegalArgumentException("external input target overlaps program, configuration or managed storage");
        }
    }

    private static String absolute(String value) {
        Objects.requireNonNull(value);
        if (!value.startsWith("/") || value.equals("/") || value.length() > 512 || value.contains("//")
                || !value.matches("/[\\p{L}\\p{N}_./ -]+") || java.util.Arrays.stream(value.substring(1).split("/", -1))
                    .anyMatch(part -> part.isEmpty() || part.equals("..") || part.equals(".")))
            throw new IllegalArgumentException("external input requires a literal absolute path");
        return value;
    }
}
