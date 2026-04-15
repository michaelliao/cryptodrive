package org.puppylab.cryptodrive.core.node;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.puppylab.cryptodrive.core.VaultContext;
import org.puppylab.cryptodrive.util.EncryptUtils;

public class CryptoNodeTest {

    VaultContext context = null;

    @BeforeEach
    void setUp() throws Exception {
        byte[] keyBytes = "32byte-skey-01234567890123456789".getBytes(StandardCharsets.UTF_8);
        SecretKey key = EncryptUtils.bytesToAesKey(keyBytes);
        this.context = new VaultContext(key);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.context.close();
    }

    @Test
    void testRoot() {
        CryptoDir root = new CryptoDir();
    }

}
