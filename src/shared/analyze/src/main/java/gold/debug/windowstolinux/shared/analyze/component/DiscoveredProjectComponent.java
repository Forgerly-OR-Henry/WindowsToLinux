package gold.debug.windowstolinux.shared.analyze.component;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import java.nio.file.Path;
import java.util.List;

/** Declared deployable roots and possible types; ambiguity is preserved for input resolution. */
public record DiscoveredProjectComponent(String id, Path relativeRoot, List<DeploymentProjectType> types) {
    /** Freezes candidate types. */
    public DiscoveredProjectComponent { types = List.copyOf(types); }
}
