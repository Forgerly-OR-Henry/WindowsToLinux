package gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.java;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenBootstrapTest {
    @TempDir
    Path root;

    @Test
    void preparesVerifiedDistributionOnlyInsideCandidate() throws Exception {
        check("missing", 0, true, true);
    }

    @Test
    void permitsRepeatedDirectoriesWithoutPermittingDuplicateFilesOrTypeChanges() throws Exception {
        check("duplicate-directory", 0, true, true);
        check("duplicate-file", 1, true, false);
        check("type-conflict", 1, true, false);
    }

    @Test
    void rejectsChecksumMismatchAndTraversalBeforeExecutingArchiveCode() throws Exception {
        check("corrupt", 1, true, false);
        check("traversal", 64, true, false);
    }

    @Test
    void retainsAccessibleHostMavenWithoutDownloading() throws Exception {
        check("host", 0, false, false);
    }

    private void check(String mode, int exit, boolean download, boolean installed) throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(bash)));
        Path directory = Files.createDirectory(root.resolve(mode));
        String preparation = MavenBuildWriter.prepareMaven();
        var checksum = Pattern.compile("'[a-f0-9]{128}'").matcher(preparation);
        assertTrue(checksum.find());
        String pinned = checksum.group();
        assertFalse(checksum.find());
        // Only substitute the synthetic fixture's digest; execute the original validation and extraction.
        preparation = preparation.replace(pinned, "\"$fixture_sha\"");
        String script = "set -euo pipefail\nexport PATH=/usr/bin:/bin\nmode=" + mode + "\n"
            + """
            mutable="$PWD/candidate"
            mkdir -p "$mutable/home" "$PWD/tool-source/apache-maven-3.9.16/bin" "$PWD/tool-source/apache-maven-3.9.16/lib/jansi-native"
            printf '#!/bin/sh\\nprintf "FIXTURE_EXECUTED=1\\\\n"\\n' > "$PWD/tool-source/apache-maven-3.9.16/bin/mvn"
            chmod 755 "$PWD/tool-source/apache-maven-3.9.16/bin/mvn"
            fixture="$PWD/distribution.tar.gz"
            if [ "$mode" = traversal ]; then
              tar -czf "$fixture" --transform 's#^apache-maven-3.9.16#../escape#' -C "$PWD/tool-source" apache-maven-3.9.16
            elif [ "$mode" = duplicate-directory ]; then
              tar -czf "$fixture" -C "$PWD/tool-source" apache-maven-3.9.16 apache-maven-3.9.16/lib/jansi-native
            elif [ "$mode" = duplicate-file ]; then
              tar -czf "$fixture" -C "$PWD/tool-source" apache-maven-3.9.16 apache-maven-3.9.16/bin/mvn
            elif [ "$mode" = type-conflict ]; then
              tar -cf "$PWD/distribution.tar" -C "$PWD/tool-source" apache-maven-3.9.16
              rmdir "$PWD/tool-source/apache-maven-3.9.16/lib/jansi-native"
              printf 'conflict' > "$PWD/tool-source/apache-maven-3.9.16/lib/jansi-native"
              tar -rf "$PWD/distribution.tar" -C "$PWD/tool-source" apache-maven-3.9.16/lib/jansi-native
              gzip -c "$PWD/distribution.tar" > "$fixture"
            else
              tar -czf "$fixture" -C "$PWD/tool-source" apache-maven-3.9.16
            fi
            fixture_sha=$(sha512sum "$fixture" | cut -d ' ' -f1)
            command() {
              if [ "$1" = -v ] && [ "$2" = mvn ]; then
                if [ "$mode" = host ]; then printf '/usr/bin/true\\n'; return 0; fi
                [ -x "$mutable/home/.wtl-tools/apache-maven-3.9.16/bin/mvn" ] || return 1
              fi
              builtin command "$@"
            }
            curl() {
              printf 'DOWNLOAD=1\\n'
              while [ "$1" != --output ]; do shift; done
              cp -- "$fixture" "$2"
              if [ "$mode" = corrupt ]; then printf 'CORRUPT' >> "$2"; fi
            }
            run() { "$@"; }
            """ + preparation
            + "case \"$mode\" in missing|duplicate-directory) run \"$maven_home/bin/mvn\" ;; esac\nprintf 'COMPLETED=1\\n'\n";
        Path log = directory.resolve("output.txt");
        var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").directory(directory.toFile())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(script.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            String output = Files.readString(log);
            assertEquals(exit, process.exitValue(), output);
            assertEquals(download, output.contains("DOWNLOAD=1"), output);
            assertEquals(installed, output.contains("BUILD_MANAGER=MAVEN:3.9.16:candidate"), output);
            assertEquals(installed, output.contains("FIXTURE_EXECUTED=1"), output);
            assertFalse(Files.exists(directory.resolve("candidate/home/.wtl-tools/escape")));
            if (installed)
                assertFalse(Files.exists(directory.resolve("candidate/home/wtl-maven.tar.gz")));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
