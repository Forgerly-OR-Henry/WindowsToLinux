package gold.debug.windowstolinux.mod.deployment.config;

import java.util.Map;
import java.util.Objects;

/** Expands named directory slots in implementation-owned resources. */
public final class DeploymentRemoteLayoutTemplate {
    private DeploymentRemoteLayoutTemplate() {
    }

    public static String render(String template, DeploymentRemoteLayout layout) {
        Objects.requireNonNull(template, "template");
        var paths = Objects.requireNonNull(layout, "layout").paths();
        var slots = Map.of(
            "@wtl:helper-directory@", paths.helperDirectory(),
            "@wtl:applications-root@", paths.applicationsRoot(),
            "@wtl:state-root@", paths.stateRoot(),
            "@wtl:configuration-root@", paths.configurationRoot(),
            "@wtl:data-root@", paths.dataRoot(),
            "@wtl:helper-directory-regex@", regex(paths.helperDirectory()),
            "@wtl:state-root-regex@", regex(paths.stateRoot()));
        String rendered = template;
        for (var slot : slots.entrySet())
            rendered = rendered.replace(slot.getKey(), slot.getValue());
        if (rendered.contains("@wtl:"))
            throw new IllegalArgumentException("Unknown managed directory template slot");
        return rendered;
    }

    private static String regex(String path) {
        return path.replace(".", "\\.");
    }
}
