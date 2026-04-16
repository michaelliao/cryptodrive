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
        byte[] data = toBytes("Hello CryptoDrive!");
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

    @Test
    void testEncryptHeader() {
        byte[] fek = toBytes("ff-123456789-123456789-123456789", 32);
        // AES Key:
        byte[] key = toBytes("32byte-skey-01234567890123456789", 32);
        SecretKey skey = EncryptUtils.bytesToAesKey(key);
        byte[] header = new byte[64];
        header[0] = 1;
        header[1] = 2;
        header[2] = 3;
        header[3] = 4;
        EncryptUtils.encrypt(fek, skey, header, 4);
        // header[0~3] unchanged:
        assertEquals(1, header[0]);
        assertEquals(2, header[1]);
        assertEquals(3, header[2]);
        assertEquals(4, header[3]);
        // now decrypt header:
        byte[] output = new byte[32];
        EncryptUtils.decrypt(header, 4, skey, output, 0);
        assertArrayEquals(fek, output);
    }

    @Test
    void testEncryptBlock() {
        byte[] plain = toBytes("A123456789".repeat(10), 100);
        // AES Key:
        byte[] key = toBytes("32byte-skey-01234567890123456789", 32);
        SecretKey skey = EncryptUtils.bytesToAesKey(key);
        byte[] block = new byte[32 + plain.length];
        block[0] = 1;
        block[1] = 2;
        block[2] = 3;
        block[3] = 4;
        EncryptUtils.encrypt(plain, skey, block, 4);
        // block[0~3] unchanged:
        assertEquals(1, block[0]);
        assertEquals(2, block[1]);
        assertEquals(3, block[2]);
        assertEquals(4, block[3]);
        // now decrypt block:
        byte[] output = new byte[plain.length];
        EncryptUtils.decrypt(block, 4, skey, output, 0);
        assertArrayEquals(plain, output);
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
