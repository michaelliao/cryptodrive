package org.puppylab.cryptodrive.core.node;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.puppylab.cryptodrive.util.EncryptUtils;

public class CryptoFsTest {

    SecretKey key = null;

    @BeforeEach
    void setUp() throws Exception {
        byte[] fixedKeyBytes = "K_0123456789_123456789_123456789".getBytes(StandardCharsets.UTF_8);
        this.key = EncryptUtils.bytesToAesKey(fixedKeyBytes);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.key = null;
    }

    @Test
    void testEmptyFS() {
        Path fsRoot = Path.of("../fs-tests/fs-empty").toAbsolutePath().normalize();
        CryptoFs fs = new CryptoFs(this.key, fsRoot);
        fs.print();
    }

    @Test
    void testLostFoundFS() {
        Path fsRoot = Path.of("../fs-tests/fs-lost-found").toAbsolutePath().normalize();
        CryptoFs fs = new CryptoFs(this.key, fsRoot);
        fs.print();
    }

}
