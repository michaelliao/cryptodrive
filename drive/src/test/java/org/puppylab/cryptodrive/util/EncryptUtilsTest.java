package org.puppylab.cryptodrive.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.puppylab.cryptodrive.core.exception.EncryptException;

public class EncryptUtilsTest {

    @Test
    void testGenerateSecureRandomBytes() {
        final int COUNT = 1000;
        Set<String> set = new HashSet<>();
        for (int i = 0; i < COUNT; i++) {
            byte[] bs = EncryptUtils.generateSecureRandomBytes(12);
            set.add(Base64Utils.b64(bs));
        }
        assertEquals(COUNT, set.size());
    }

    @Test
    void testPbeKey() {
        char[] password = "HelloMyPassword".toCharArray();
        byte[] salt = toBytes("32byte-salt-01234567890123456789", 32);
        byte[] key = EncryptUtils.derivePbeKey(password, salt, 1000);
        assertEquals(32, key.length);
        assertEquals("tIN93XrCsToiIhqiV6egL57-mB4Eg6aiMsShYFYRmeA", Base64Utils.b64(key));
    }

    @Test
    void testEncrypt() throws Exception {
        byte[] data = toBytes("Hello MyPassword!");
        // AES Key:
        byte[] key = toBytes("32byte-skey-01234567890123456789", 32);
        SecretKey skey = EncryptUtils.bytesToAesKey(key);
        Arrays.fill(key, (byte) 0);
        // encrypt:
        byte[] cdata = EncryptUtils.encrypt(data, skey);
        // decrypt ok:
        byte[] decrypted = EncryptUtils.decrypt(cdata, skey);
        assertArrayEquals(data, decrypted);
        // decrypt failed:
        assertThrows(EncryptException.class, () -> {
            byte[] badKey = toBytes("32byte-skey-0123456789012345678x", 32);
            EncryptUtils.decrypt(cdata, EncryptUtils.bytesToAesKey(badKey));
        });
        skey.destroy();
    }

    @Test
    void testEncryptKey() {
        byte[] key = toBytes("32byte-skey-01234567890123456789", 32);
        SecretKey kek = EncryptUtils.bytesToAesKey(toBytes("32byte-kek-012345678901234567890", 32));
        // encrypt:
        byte[] cdata = EncryptUtils.encrypt(key, kek);
        assertEquals(12 + 16 + 32, cdata.length);
        // decrypt:
        assertArrayEquals(key, EncryptUtils.decrypt(cdata, kek));
    }

    byte[] toBytes(String s) {
        return toBytes(s, s.length());
    }

    byte[] toBytes(String s, int expectedLength) {
        byte[] bs = s.getBytes(StandardCharsets.UTF_8);
        assertEquals(expectedLength, bs.length);
        return bs;
    }
}
