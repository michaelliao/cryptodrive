package org.puppylab.cryptodrive.util;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;

import org.puppylab.cryptodrive.core.exception.EncryptException;

public class EncryptUtils {

    public static final String AES_ALG       = "AES/GCM/NoPadding";
    public static final int    AES_KEY_SIZE  = 256;
    public static final int    AES_KEY_BYTES = AES_KEY_SIZE / 8;

    public static final int AES_TAG_BYTES = 16;
    public static final int AES_TAG_SIZE  = AES_TAG_BYTES * 8;

    public static final int AES_IV_BYTES = 12;

    public static final String PBE_ALG        = "PBKDF2WithHmacSHA256";
    public static final int    PBE_KEY_SIZE   = 256;
    public static final int    PBE_ITERATIONS = 100_000;

    private static final SecureRandom srandom = new SecureRandom();

    /**
     * Derive a PBE key.
     */
    public static byte[] derivePbeKey(char[] password, byte[] salt, int iterations) {
        try {
            KeySpec spec = new PBEKeySpec(password, salt, iterations, PBE_KEY_SIZE);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBE_ALG);
            return factory.generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new EncryptException(e);
        }
    }

    /**
     * Generate random 12 bytes IV.
     */
    public static byte[] generateIV() {
        return generateSecureRandomBytes(AES_IV_BYTES);
    }

    /**
     * Generate random 32 bytes salt.
     */
    public static byte[] generateSalt() {
        return generateSecureRandomBytes(32);
    }

    /**
     * Generate random 32 bytes key.
     */
    public static byte[] generateKey() {
        return generateSecureRandomBytes(32);
    }

    public static byte[] generateSecureRandomBytes(int size) {
        byte[] buffer = new byte[size];
        srandom.nextBytes(buffer);
        return buffer;
    }

    public static SecretKey bytesToAesKey(byte[] key) {
        return new AesSecretKey(key);
    }

    /**
     * Encrypt data by AES-GCM. Return [iv + encrypted_data].
     */
    public static byte[] encrypt(byte[] pdata, SecretKey key) {
        byte[] iv = generateIV();
        // copy iv to buffer:
        byte[] buffer = Arrays.copyOf(iv, pdata.length + AES_IV_BYTES + AES_TAG_BYTES);
        try {
            Cipher cipher = Cipher.getInstance(AES_ALG);
            GCMParameterSpec spec = new GCMParameterSpec(AES_TAG_SIZE, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            cipher.doFinal(pdata, 0, pdata.length, buffer, AES_IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new EncryptException(e);
        }
        return buffer;
    }

    /**
     * Encrypt pdata to output at offset. Result:
     * 
     * output[offset, offset+12]: iv
     * 
     * output[offset+12, offset+12+length]: encryption
     * 
     * data output[offset+12+length, offset+12+length+16]: tag
     */
    public static void encrypt(byte[] pdata, SecretKey key, byte[] output, int outOffset) {
        byte[] iv = generateIV();
        try {
            Cipher cipher = Cipher.getInstance(AES_ALG);
            GCMParameterSpec spec = new GCMParameterSpec(AES_TAG_SIZE, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            cipher.doFinal(pdata, 0, pdata.length, output, AES_IV_BYTES + outOffset);
        } catch (GeneralSecurityException e) {
            throw new EncryptException(e);
        }
        // copy iv to output:
        System.arraycopy(iv, 0, output, outOffset, AES_IV_BYTES);
    }

    public static byte[] decrypt(byte[] edata, SecretKey key) {
        byte[] output = new byte[edata.length - AES_IV_BYTES - AES_TAG_BYTES];
        decrypt(edata, 0, key, output, 0);
        return output;
    }

    /**
     * Decrypt edata to output at offset.
     * 
     * edata[srcOffset, srcOffset+12]: iv
     * 
     * edata[srcOffset+12, ...]: encrypted data + tag
     * 
     * output[offset, ...]: decrypted data
     * 
     */
    public static void decrypt(byte[] edata, int srcOffset, SecretKey key, byte[] output, int outOffset) {
        byte[] iv = new byte[AES_IV_BYTES];
        System.arraycopy(edata, srcOffset, iv, 0, AES_IV_BYTES);
        try {
            Cipher cipher = Cipher.getInstance(AES_ALG);
            GCMParameterSpec spec = new GCMParameterSpec(AES_TAG_SIZE, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            cipher.doFinal(edata, srcOffset + AES_IV_BYTES, edata.length - srcOffset - AES_IV_BYTES, output, outOffset);
        } catch (GeneralSecurityException e) {
            throw new EncryptException(e);
        }
    }

    public static class AesSecretKey implements SecretKey {

        private byte[] key;

        @Override
        public void destroy() {
            Arrays.fill(this.key, (byte) 0);
            this.key = null;
        }

        @Override
        public boolean isDestroyed() {
            // TODO Auto-generated method stub
            return this.key == null;
        }

        public AesSecretKey(byte[] key) {
            this.key = Arrays.copyOf(key, key.length);
        }

        @Override
        public String getAlgorithm() {
            return "AES";
        }

        @Override
        public String getFormat() {
            return "RAW";
        }

        @Override
        public byte[] getEncoded() {
            return this.key;
        }
    }
}
