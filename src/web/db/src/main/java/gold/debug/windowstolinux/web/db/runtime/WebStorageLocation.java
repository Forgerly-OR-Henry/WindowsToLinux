package gold.debug.windowstolinux.web.db.runtime;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Resolves storage against this DB module's actual class or external JAR location. */
public record WebStorageLocation(Path root, Mode mode) {
    public enum Mode { CLASS, JAR }
    public static final String DATABASE_NAME = "windowstolinuxweb.db";

    public static WebStorageLocation resolve(String configuredRoot) throws IOException {
        try {
            return resolve(configuredRoot, WebStorageLocation.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException | NullPointerException failure) {
            throw new IOException("Cannot identify the Web DB module code source", failure);
        }
    }

    static WebStorageLocation resolve(String configuredRoot, URI codeSource) throws IOException {
        if (!"file".equals(codeSource.getScheme())) throw new IOException("Web DB must load from classes or an external JAR");
        Path source = Path.of(codeSource).toAbsolutePath().normalize();
        Mode mode;
        Path base;
        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && source.toString().endsWith(".jar")) {
            mode = Mode.JAR;
            base = source.getParent();
        } else if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            mode = Mode.CLASS;
            base = moduleRoot(source);
        } else throw new IOException("Unrecognized Web DB code source: " + source);
        if (configuredRoot == null || configuredRoot.isBlank()) throw new IOException("w2l.storage.root is required");
        Path configured = Path.of(configuredRoot);
        if (configured.isAbsolute()) return new WebStorageLocation(configured.normalize(), mode);
        if (!configuredRoot.equals("db.data") && !configuredRoot.startsWith("db.data/"))
            throw new IOException("w2l.storage.root must be db.data, db.data/subdirectory, or an absolute path");
        Path data = base.resolve("data");
        Path root = configuredRoot.equals("db.data") ? data : data.resolve(configuredRoot.substring(8));
        root = root.normalize();
        if (!root.startsWith(data) || configuredRoot.indexOf('\\') >= 0)
            throw new IOException("The logical data location must stay beneath db.data");
        return new WebStorageLocation(root, mode);
    }

    private static Path moduleRoot(Path source) throws IOException {
        for (Path candidate = source; candidate != null; candidate = candidate.getParent()) {
            Path pom = candidate.resolve("pom.xml");
            if (Files.isRegularFile(pom) && Files.readString(pom).contains("<artifactId>windowstolinux-web-db</artifactId>"))
                return candidate;
        }
        throw new IOException("Cannot identify the Web DB module root from its classes: " + source);
    }

    public Path database() { return root.resolve(DATABASE_NAME); }
    public Path files() { return root.resolve("files"); }
    public Path backups() { return root.resolve("backups"); }
    public String jdbcUrl() { return "jdbc:sqlite:" + database(); }
}
