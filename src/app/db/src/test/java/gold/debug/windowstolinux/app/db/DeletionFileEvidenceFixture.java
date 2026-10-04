package gold.debug.windowstolinux.app.db;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;

import gold.debug.windowstolinux.app.db.mod.ModDeletionLedger;

/** Controlled ordinary-file observations for ledger tests; installation security is tested in service/main. */
final class DeletionFileEvidenceFixture {
    static Verifier files() {
        return new Verifier();
    }

    static final class Verifier implements ModDeletionLedger.FileEvidence {
        SQLException failure;

        int checks;

        public void verifyHome(Path home) throws SQLException {
            if (!home.isAbsolute() || !home.normalize().equals(home))
                throw new SQLException("Invalid fixture home");
        }

        public void verifyAbsent(Path path) throws SQLException {
            checks++;
            if (failure != null)
                throw failure;
            try {
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                throw new SQLException("Fixture target still exists");
            } catch (NoSuchFileException absent) {
                // The fixture controls these ordinary files. Security checks live in the installation tests.
            } catch (IOException unavailable) {
                throw new SQLException("Fixture target unavailable", unavailable);
            }
        }
    }
}
