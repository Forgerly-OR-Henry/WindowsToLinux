package gold.debug.windowstolinux.shared.analyze.source;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.application.ApplicationCommand;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Reads build ownership before component discovery; never executes declarations. */
public final class ApplicationBundleInspector {
    private ApplicationBundleInspector() { }
    public static Properties declaration(Path root) throws IOException {
        Path file = root.resolve("windowstolinux-application.properties");
        Properties properties = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("duplicate application declaration");
                return super.put(key, value);
            }
        };
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return properties;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file) || Files.size(file) > 65536)
            throw new IOException("invalid application declaration");
        try (var reader = Files.newBufferedReader(file)) { properties.load(reader); }
        if (!properties.getProperty("version", "1").equals("1")) throw new IOException("unsupported application declaration version");
        return properties;
    }
    public static Path mainRoot(Path root, Properties properties) throws IOException {
        String directory = ApplicationCommand.relative(properties.getProperty("buildDirectory", ""), true);
        Path target = root.resolve(directory).normalize();
        if (!target.startsWith(root) || !target.toRealPath().equals(root.toRealPath().resolve(directory).normalize())
                || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("application build directory escapes the source bundle");
        return target;
    }
    public static Optional<DeploymentProjectType> type(Properties properties) {
        return Optional.ofNullable(properties.getProperty("projectType")).map(DeploymentProjectType::valueOf);
    }
    public static List<Path> companions(Path root, Properties properties) throws IOException {
        String ids = properties.getProperty("companions", "");
        if (ids.isBlank()) return List.of();
        var result = new ArrayList<Path>();
        for (String id : ids.split(",", -1)) {
            if (result.size() >= 32 || !id.trim().matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IOException("invalid companion identifier");
            if (!properties.getProperty("companion." + id.trim() + ".type", "").equals("CMAKE_SERVICE"))
                throw new IOException("companion build type requires a supported C/C++ build declaration");
            Properties source = new Properties(); source.setProperty("buildDirectory", properties.getProperty("companion." + id.trim() + ".sourcePath", ""));
            Path target = mainRoot(root, source);
            if (target.equals(root) || result.contains(target)) throw new IOException("conflicting companion source");
            result.add(target);
        }
        return List.copyOf(result);
    }
}
