package gold.debug.windowstolinux.mod.deployment.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class DeploymentConfigPathsTest {
    @Test
    void acceptsDedicatedRootsAndSharedPrefixesWithoutChangingInput() {
        var remote = new DeploymentConfig.RemotePaths("/srv/WTL-layout_A", "/srv/WTL-layout_A-apps",
            "/srv/WTL-layout_A-state", "/srv/WTL-layout_A-config", "/srv/WTL-layout_A-data");
        assertEquals("/srv/WTL-layout_A", remote.helperDirectory());
        assertEquals("/srv/WTL-layout_A-apps", remote.applicationsRoot());
        assertEquals("/srv/WTL-layout_A-state", remote.stateRoot());
        assertEquals("/srv/WTL-layout_A-config", remote.configurationRoot());
        assertEquals("/srv/WTL-layout_A-data", remote.dataRoot());
    }

    @Test
    void rejectsNonCanonicalAndExecutablePathSyntax() {
        for (String path : List.of("", "/", "relative/path", "./srv/helper", " /srv/helper", "/srv/helper ",
            "/srv/helper/", "//srv/helper", "/srv//helper", "/srv/./helper", "/srv/../helper", "/srv/helper/..",
            "/srv/helper/.", "C:/helper", "/srv\\helper", "/srv/helper dir", "/srv/${HOME}", "/srv/$HOME",
            "/srv/$(id)", "/srv/`id`", "/srv/helper;id", "/srv/helper|id", "/srv/helper\nnext", "/srv/helper\0",
            "/srv/" + "a".repeat(508)))
            assertThrows(IllegalArgumentException.class, () -> withHelper(path), path);
    }

    @Test
    void rejectsSystemDirectoriesAndControlTrees() {
        for (String path : List.of("/etc", "/etc/opt", "/usr", "/usr/local", "/usr/local/lib", "/var", "/var/lib",
            "/var/opt", "/opt", "/srv", "/proc", "/proc/wtl", "/sys/wtl", "/dev/wtl", "/run/credentials/wtl",
            "/tmp/wtl", "/var/tmp/wtl", "/etc/systemd/system/wtl", "/etc/containers/systemd/wtl"))
            assertThrows(IllegalArgumentException.class, () -> withHelper(path), path);
        assertEquals("/srv/proc-data", withHelper("/srv/proc-data").helperDirectory());
    }

    @Test
    void rejectsEqualOrNestedRootsInEitherDirection() {
        assertThrows(IllegalArgumentException.class, () -> new DeploymentConfig.RemotePaths("/srv/shared", "/srv/shared",
            "/srv/state", "/srv/config", "/srv/data"));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentConfig.RemotePaths("/srv/shared", "/srv/shared/apps",
            "/srv/state", "/srv/config", "/srv/data"));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentConfig.RemotePaths("/srv/shared/helper", "/srv/shared",
            "/srv/state", "/srv/config", "/srv/data"));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentConfig.RemotePaths("/srv/helper", "/srv/apps",
            "/srv/shared", "/srv/shared/config", "/srv/data"));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentConfig.RemotePaths("/srv/helper", "/srv/apps",
            "/srv/state", "/srv/shared/config", "/srv/shared"));
    }

    @Test
    void requiresThePathsGroupAndEveryRoot() throws Exception {
        var config = DeploymentConfigLoader.current();
        assertThrows(NullPointerException.class, () -> new DeploymentConfig(config.source(), config.build(),
            config.environment(), config.view(), config.ai(), null));
        var remote = DeploymentConfig.RemotePaths.original();
        for (int missing = 0; missing < 5; missing++) {
            String[] roots = {remote.helperDirectory(), remote.applicationsRoot(), remote.stateRoot(), remote.configurationRoot(), remote.dataRoot()};
            roots[missing] = null;
            assertThrows(NullPointerException.class,
                () -> new DeploymentConfig.RemotePaths(roots[0], roots[1], roots[2], roots[3], roots[4]));
        }
    }

    private static DeploymentConfig.RemotePaths withHelper(String path) {
        var original = DeploymentConfig.RemotePaths.original();
        return new DeploymentConfig.RemotePaths(path, original.applicationsRoot(), original.stateRoot(),
            original.configurationRoot(), original.dataRoot());
    }
}
