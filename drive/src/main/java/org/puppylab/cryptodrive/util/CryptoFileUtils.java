package org.puppylab.cryptodrive.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import javax.crypto.SecretKey;

/**
 * Header / block codec for {@code .c9e} encryption files.
 *
 * <pre>
 * Layout on disk:
 *   [ header (64 B) ]
 *   [ block 0 (&le; 32 800 B) ]
 *   [ block 1 ]
 *   ...
 *
 * Header (64 B):
 *   4 B  (version, reserved, reserved, reserved) current version = 1
 *  12 B  IV
 *  32 B  FEK ciphertext (AES-GCM(DEK))
 *  16 B  GCM tag
 *
 * Block (up to 32 800 B):
 *   4 B  meta (reserved, currently 0)
 *  12 B  IV
 *   N B  data ciphertext, N in [0, 32 768]
 *  16 B  GCM tag
 * </pre>
 */
public final class CryptoFileUtils {

    public static final int  HEADER_SIZE        = 64;
    public static final byte HEADER_VERSION     = 1;
    public static final int  HEADER_WRAP_OFFSET = 4; // IV|enc FEK|tag (60 B) lives here

    public static final int BLOCK_META_SIZE  = 4;
    public static final int BLOCK_IV_SIZE    = EncryptUtils.AES_IV_BYTES;                        // 12
    public static final int BLOCK_TAG_SIZE   = EncryptUtils.AES_TAG_BYTES;                       // 16
    public static final int BLOCK_OVERHEAD   = BLOCK_META_SIZE + BLOCK_IV_SIZE + BLOCK_TAG_SIZE; // 32
    public static final int BLOCK_PLAIN_SIZE = 32 * 1024;                                        // 32 768
    public static final int BLOCK_ENC_SIZE   = BLOCK_PLAIN_SIZE + BLOCK_OVERHEAD;                // 32 800

    private static final int FEK_BYTES = EncryptUtils.AES_KEY_BYTES; // 32

    private CryptoFileUtils() {
    }

    // ─── header ────────────────────────────────────────────────────────────

    /**
     * Generate a random FEK, wrap it with {@code dek}, and return the 64-byte
     * header ready to write at offset 0. The caller receives the raw FEK bytes via
     * the return record for caching in the open-file handle.
     */
    public static HeaderInit generateHeader(SecretKey dek) {
        byte[] fek = EncryptUtils.generateKey(); // 32 B
        byte[] header = new byte[HEADER_SIZE];
        // u8 version at [0]; [1..4) reserved (zero from fresh array)
        header[0] = HEADER_VERSION;
        // wrap FEK in place into header[4..64): IV(12) | ct(32) | tag(16)
        EncryptUtils.encrypt(fek, dek, header, HEADER_WRAP_OFFSET);
        return new HeaderInit(header, EncryptUtils.bytesToAesKey(fek));
    }

    /** Read the 64-byte header at offset 0, verify version, and unwrap the FEK. */
    public static SecretKey readHeader(FileChannel ch, SecretKey dek) throws IOException {
        byte[] h = new byte[HEADER_SIZE];
        int n = readFully(ch, ByteBuffer.wrap(h), 0);
        if (n != HEADER_SIZE) {
            throw new IOException("truncated header: " + n + " bytes");
        }
        int version = h[0];
        if (version != HEADER_VERSION) {
            throw new IOException("unsupported encryption version: " + version);
        }
        // h[1..4) reserved. Unwrap directly out of h[4..64) into the FEK buffer.
        byte[] fek = new byte[FEK_BYTES];
        EncryptUtils.decrypt(h, HEADER_WRAP_OFFSET, dek, fek, 0);
        return EncryptUtils.bytesToAesKey(fek);
    }

    /** Write the 64-byte header at offset 0. */
    public static void writeHeader(FileChannel ch, byte[] header) throws IOException {
        if (header.length != HEADER_SIZE) {
            throw new IllegalArgumentException("header must be " + HEADER_SIZE + " bytes");
        }
        writeFully(ch, ByteBuffer.wrap(header), 0);
    }

    // ─── blocks ────────────────────────────────────────────────────────────

    /**
     * Decrypt block {@code blockIndex} (0-based) and return its plaintext. Returns
     * an empty array if the block is beyond EOF.
     */
    public static byte[] readBlock(FileChannel ch, SecretKey fek, long blockIndex) throws IOException {
        long fileLen = ch.size();
        long blockStart = HEADER_SIZE + blockIndex * BLOCK_ENC_SIZE;
        if (blockStart >= fileLen) {
            return new byte[0];
        }
        int encLen = (int) Math.min(BLOCK_ENC_SIZE, fileLen - blockStart);
        if (encLen < BLOCK_OVERHEAD) {
            throw new IOException("corrupt block at index " + blockIndex + ": only " + encLen + " bytes");
        }
        // raw layout: meta(4) | IV(12) | ct | tag(16). decrypt reads from
        // srcOffset=4 to end of raw, so no envelope copy is needed.
        byte[] raw = new byte[encLen];
        readFully(ch, ByteBuffer.wrap(raw), blockStart);
        byte[] plain = new byte[encLen - BLOCK_OVERHEAD];
        EncryptUtils.decrypt(raw, BLOCK_META_SIZE, fek, plain, 0);
        return plain;
    }

    /**
     * Encrypt {@code plain} (0..{@link #BLOCK_PLAIN_SIZE} bytes) as block
     * {@code blockIndex}. The block is written in full and will truncate any tail
     * beyond it.
     */
    public static void writeBlock(FileChannel ch, SecretKey fek, long blockIndex, byte[] plain) throws IOException {
        if (plain.length > BLOCK_PLAIN_SIZE) {
            throw new IllegalArgumentException("plain block > " + BLOCK_PLAIN_SIZE);
        }
        // single allocation: meta(4) | IV(12) | ct | tag(16). meta stays zero
        // (fresh array); encrypt fills IV+ct+tag in place starting at offset 4.
        byte[] raw = new byte[BLOCK_OVERHEAD + plain.length];
        EncryptUtils.encrypt(plain, fek, raw, BLOCK_META_SIZE);
        long blockStart = HEADER_SIZE + blockIndex * BLOCK_ENC_SIZE;
        writeFully(ch, ByteBuffer.wrap(raw), blockStart);
    }

    // ─── size helpers ──────────────────────────────────────────────────────

    /**
     * Plain file size for an encryption file of length {@code encLen}.
     * {@code encLen == 0} (missing or empty blob) → {@code 0}.
     */
    public static long getPlainFileSize(long encLen) {
        if (encLen == 0)
            return 0;
        if (encLen < HEADER_SIZE) {
            throw new IllegalStateException("corrupt encryption file: " + encLen + " bytes");
        }
        long body = encLen - HEADER_SIZE;
        if (body == 0)
            return 0;
        long fullBlocks = body / BLOCK_ENC_SIZE;
        long lastBlock = body % BLOCK_ENC_SIZE;
        if (lastBlock != 0 && lastBlock < BLOCK_OVERHEAD) {
            throw new IllegalStateException("corrupt tail block: " + lastBlock + " bytes");
        }
        return (long) BLOCK_PLAIN_SIZE * fullBlocks + Math.max(0, lastBlock - BLOCK_OVERHEAD);
    }

    /** Encryption file length on disk for a plain size of {@code plainLen}. */
    public static long getEncryptionFileSize(long plainLen) {
        if (plainLen == 0)
            return HEADER_SIZE;
        long fullBlocks = plainLen / BLOCK_PLAIN_SIZE;
        long tail = plainLen % BLOCK_PLAIN_SIZE;
        long body = fullBlocks * BLOCK_ENC_SIZE + (tail == 0 ? 0 : tail + BLOCK_OVERHEAD);
        return HEADER_SIZE + body;
    }

    // ─── internals ─────────────────────────────────────────────────────────

    private static int readFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        int total = 0;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, position + total);
            if (n < 0)
                break;
            total += n;
        }
        return total;
    }

    private static void writeFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        while (buf.hasRemaining()) {
            position += ch.write(buf, position);
        }
    }

    /**
     * Result of {@link #generateHeader(SecretKey)}: serialized header + the FEK to
     * cache.
     */
    public record HeaderInit(byte[] header, SecretKey fek) {
    }
}
