package org.puppylab.cryptodrive.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import javax.crypto.SecretKey;

import org.puppylab.cryptodrive.core.exception.EncryptException;

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
 *   4 B  version (little-endian u32, current = 1)
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

    public static final int HEADER_SIZE       = 64;
    public static final int HEADER_VERSION    = 1;
    public static final int HEADER_VERSION_OFFSET = 0;
    public static final int HEADER_WRAP_OFFSET    = 4;  // IV|enc FEK|tag (60 B) lives here

    public static final int BLOCK_META_SIZE   = 4;
    public static final int BLOCK_IV_SIZE     = EncryptUtils.AES_IV_BYTES;   // 12
    public static final int BLOCK_TAG_SIZE    = EncryptUtils.AES_TAG_BYTES;  // 16
    public static final int BLOCK_OVERHEAD    = BLOCK_META_SIZE + BLOCK_IV_SIZE + BLOCK_TAG_SIZE; // 32
    public static final int BLOCK_PLAIN_SIZE  = 32 * 1024;                   // 32 768
    public static final int BLOCK_ENC_SIZE    = BLOCK_PLAIN_SIZE + BLOCK_OVERHEAD; // 32 800

    private static final int FEK_BYTES  = EncryptUtils.AES_KEY_BYTES; // 32
    private static final int FEK_WRAPPED_SIZE = FEK_BYTES + BLOCK_IV_SIZE + BLOCK_TAG_SIZE; // 60

    private CryptoFileUtils() {}

    // ─── header ────────────────────────────────────────────────────────────

    /**
     * Generate a random FEK, wrap it with {@code dek}, and return the 64-byte
     * header ready to write at offset 0. The caller receives the raw FEK bytes
     * via the return record for caching in the open-file handle.
     */
    public static HeaderInit generateHeader(SecretKey dek) {
        byte[] fek = EncryptUtils.generateKey();                 // 32 B
        byte[] wrapped = EncryptUtils.encrypt(fek, dek);         // 12 + 32 + 16 = 60 B
        if (wrapped.length != FEK_WRAPPED_SIZE) {
            throw new EncryptException("unexpected wrap size: " + wrapped.length);
        }
        byte[] header = new byte[HEADER_SIZE];
        // little-endian u32 version
        header[0] = (byte) (HEADER_VERSION & 0xff);
        header[1] = (byte) ((HEADER_VERSION >>> 8) & 0xff);
        header[2] = (byte) ((HEADER_VERSION >>> 16) & 0xff);
        header[3] = (byte) ((HEADER_VERSION >>> 24) & 0xff);
        System.arraycopy(wrapped, 0, header, HEADER_WRAP_OFFSET, FEK_WRAPPED_SIZE);
        return new HeaderInit(header, EncryptUtils.bytesToAesKey(fek));
    }

    /** Read the 64-byte header at offset 0, verify version, and unwrap the FEK. */
    public static SecretKey readHeader(FileChannel ch, SecretKey dek) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
        int n = readFully(ch, buf, 0);
        if (n != HEADER_SIZE) {
            throw new IOException("truncated header: " + n + " bytes");
        }
        byte[] h = buf.array();
        int version = (h[0] & 0xff)
                   | ((h[1] & 0xff) << 8)
                   | ((h[2] & 0xff) << 16)
                   | ((h[3] & 0xff) << 24);
        if (version != HEADER_VERSION) {
            throw new IOException("unsupported encryption version: " + version);
        }
        byte[] wrapped = new byte[FEK_WRAPPED_SIZE];
        System.arraycopy(h, HEADER_WRAP_OFFSET, wrapped, 0, FEK_WRAPPED_SIZE);
        byte[] fek = EncryptUtils.decrypt(wrapped, dek);
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
        ByteBuffer buf = ByteBuffer.allocate(encLen);
        readFully(ch, buf, blockStart);
        // skip 4 B meta → treat [IV|data|tag] as a standard EncryptUtils envelope
        byte[] envelope = new byte[encLen - BLOCK_META_SIZE];
        System.arraycopy(buf.array(), BLOCK_META_SIZE, envelope, 0, envelope.length);
        return EncryptUtils.decrypt(envelope, fek);
    }

    /**
     * Encrypt {@code plain} (0..{@link #BLOCK_PLAIN_SIZE} bytes) as block
     * {@code blockIndex}. The block is written in full and will truncate any
     * tail beyond it.
     */
    public static void writeBlock(FileChannel ch, SecretKey fek, long blockIndex, byte[] plain) throws IOException {
        if (plain.length > BLOCK_PLAIN_SIZE) {
            throw new IllegalArgumentException("plain block > " + BLOCK_PLAIN_SIZE);
        }
        byte[] envelope = EncryptUtils.encrypt(plain, fek);      // IV|ct|tag = 12+len+16
        ByteBuffer out = ByteBuffer.allocate(BLOCK_META_SIZE + envelope.length);
        out.putInt(0);                                           // reserved meta = 0 (big-endian; reserved so endianness doesn't matter)
        out.put(envelope);
        out.flip();
        long blockStart = HEADER_SIZE + blockIndex * BLOCK_ENC_SIZE;
        writeFully(ch, out, blockStart);
    }

    // ─── size helpers ──────────────────────────────────────────────────────

    /**
     * Plain file size for an encryption file of length {@code encLen}.
     * {@code encLen == 0} (missing or empty blob) → {@code 0}.
     */
    public static long getPlainFileSize(long encLen) {
        if (encLen == 0) return 0;
        if (encLen < HEADER_SIZE) {
            throw new IllegalStateException("corrupt encryption file: " + encLen + " bytes");
        }
        long body = encLen - HEADER_SIZE;
        if (body == 0) return 0;
        long fullBlocks = body / BLOCK_ENC_SIZE;
        long lastBlock  = body % BLOCK_ENC_SIZE;
        if (lastBlock != 0 && lastBlock < BLOCK_OVERHEAD) {
            throw new IllegalStateException("corrupt tail block: " + lastBlock + " bytes");
        }
        return (long) BLOCK_PLAIN_SIZE * fullBlocks + Math.max(0, lastBlock - BLOCK_OVERHEAD);
    }

    /** Encryption file length on disk for a plain size of {@code plainLen}. */
    public static long getEncryptionFileSize(long plainLen) {
        if (plainLen == 0) return HEADER_SIZE;
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
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static void writeFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        while (buf.hasRemaining()) {
            position += ch.write(buf, position);
        }
    }

    /** Result of {@link #generateHeader(SecretKey)}: serialized header + the FEK to cache. */
    public record HeaderInit(byte[] header, SecretKey fek) {}
}
