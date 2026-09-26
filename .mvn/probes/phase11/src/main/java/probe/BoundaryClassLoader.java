package probe;

import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.Enumeration;
import java.util.Collections;
import org.pf4j.PluginClassLoader;
import org.pf4j.DefaultPluginDescriptor;

public final class BoundaryClassLoader extends PluginClassLoader {
    private final Map<String, BoundaryClassLoader> exports;
    public BoundaryClassLoader(String id, List<URL> sources, Map<String, BoundaryClassLoader> exports) {
        super(null, new DefaultPluginDescriptor(id, "", "", "1.0.0", "*", "", ""), BoundaryClassLoader.class.getClassLoader());
        this.exports = Map.copyOf(exports);
        for (var first : exports.keySet()) {
            if (first.equals(id) || first.startsWith(id + ".") || id.startsWith(first + ".")) throw new IllegalArgumentException("Export overlaps own package");
            for (var second : exports.keySet()) {
                if (!first.equals(second) && first.startsWith(second + ".")) throw new IllegalArgumentException("Ambiguous exports");
            }
        }
        sources.forEach(this::addURL);
    }
    @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            if (isClosed()) throw new ClassNotFoundException("Loader closed: " + name);
            var loaded = findLoadedClass(name);
            if (loaded != null) return loaded;
            if (name.startsWith("gold.debug.windowstolinux.appapi.")) return getParent().loadClass(name);
            try { return ClassLoader.getPlatformClassLoader().loadClass(name); }
            catch (ClassNotFoundException notJdk) {
                for (var entry : exports.entrySet()) {
                    if (name.startsWith(entry.getKey() + ".")) return entry.getValue().loadClass(name);
                }
                return findClass(name);
            }
        }
    }
    @Override public URL getResource(String name) {
        if (isClosed()) return null;
        for (var entry : exports.entrySet()) {
            if (name.startsWith(entry.getKey().replace('.', '/') + "/")) return entry.getValue().getResource(name);
        }
        return uncached(findResource(name));
    }
    @Override public Enumeration<URL> getResources(String name) throws IOException {
        if (isClosed()) return Collections.emptyEnumeration();
        for (var entry : exports.entrySet()) {
            if (name.startsWith(entry.getKey().replace('.', '/') + "/")) return entry.getValue().getResources(name);
        }
        var all = new java.util.ArrayList<URL>();
        var found = findResources(name);
        while (found.hasMoreElements()) all.add(uncached(found.nextElement()));
        return Collections.enumeration(all);
    }
    private URL uncached(URL original) {
        if (original == null) return null;
        try {
            return new URL(null, original.toExternalForm(), new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL ignored) throws IOException {
                    var connection = original.openConnection();
                    connection.setUseCaches(false);
                    return connection;
                }
            });
        } catch (java.net.MalformedURLException error) { throw new IllegalStateException(error); }
    }
}
