package gold.debug.windowstolinux.shared.linux.connection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class SshCredentialTest {
    @Test
    void passwordDuplicatesOwnIndependentClearableMaterial() {
        SshCredential.Password original = new SshCredential.Password("secret".toCharArray());
        SshCredential.Password duplicate = (SshCredential.Password) original.duplicate();

        assertNotSame(original, duplicate);
        assertArrayEquals("secret".toCharArray(), original.copy());
        assertArrayEquals("secret".toCharArray(), duplicate.copy());

        duplicate.clear();
        assertArrayEquals("secret".toCharArray(), original.copy());
        assertArrayEquals(new char[6], duplicate.copy());

        original.clear();
        assertArrayEquals(new char[6], original.copy());
    }
}
