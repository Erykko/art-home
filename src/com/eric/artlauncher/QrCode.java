package com.eric.artlauncher;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.nio.charset.StandardCharsets;

/**
 * Minimal QR code encoder: byte mode, error correction level M, versions 1-6 (up to 106 bytes,
 * plenty for a LAN URL), fixed mask 0. Structure follows the QR spec (ISO/IEC 18004).
 */
public final class QrCode {

    // Per version 1..6 at level M: data codewords per block, number of blocks, EC codewords per block.
    private static final int[] DATA_PER_BLOCK = {0, 16, 28, 44, 32, 43, 27};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4};
    private static final int[] EC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16};
    private static final int[] ALIGN = {0, 0, 18, 22, 26, 30, 34};

    private final int size;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    private QrCode(int version) {
        size = version * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];
    }

    public static Bitmap bitmap(String text, int scale) {
        boolean[][] m = encode(text);
        int quiet = 4, n = m.length, px = (n + quiet * 2) * scale;
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.WHITE);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (!m[y][x]) continue;
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        bmp.setPixel((x + quiet) * scale + dx, (y + quiet) * scale + dy, Color.BLACK);
                    }
                }
            }
        }
        return bmp;
    }

    public static boolean[][] encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int version = 1;
        while (version <= 6 && DATA_PER_BLOCK[version] * BLOCKS[version] < bytes.length + 2) version++;
        if (version > 6) throw new IllegalArgumentException("Text too long for QR v6");

        QrCode qr = new QrCode(version);
        qr.drawFunctionPatterns(version);
        qr.drawCodewords(addEcAndInterleave(dataCodewords(bytes, version), version));
        qr.applyMask0();
        qr.drawFormatBits();
        return qr.modules;
    }

    // ---------------------------------------------------------------- data

    private static byte[] dataCodewords(byte[] bytes, int version) {
        int capacity = DATA_PER_BLOCK[version] * BLOCKS[version];
        BitBuffer bb = new BitBuffer();
        bb.append(0b0100, 4);           // byte mode
        bb.append(bytes.length, 8);     // character count (versions 1-9)
        for (byte b : bytes) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capacity * 8 - bb.length));
        bb.append(0, (8 - bb.length % 8) % 8);
        byte[] out = new byte[capacity];
        int n = bb.length / 8;
        for (int i = 0; i < n; i++) out[i] = (byte) bb.byteAt(i);
        for (int i = n, pad = 0xEC; i < capacity; i++, pad ^= 0xEC ^ 0x11) out[i] = (byte) pad;
        return out;
    }

    private static byte[] addEcAndInterleave(byte[] data, int version) {
        int blocks = BLOCKS[version], per = DATA_PER_BLOCK[version], ec = EC_PER_BLOCK[version];
        byte[] divisor = rsDivisor(ec);
        byte[][] dataBlocks = new byte[blocks][per];
        byte[][] ecBlocks = new byte[blocks][];
        for (int b = 0; b < blocks; b++) {
            System.arraycopy(data, b * per, dataBlocks[b], 0, per);
            ecBlocks[b] = rsRemainder(dataBlocks[b], divisor);
        }
        byte[] out = new byte[blocks * (per + ec)];
        int k = 0;
        for (int i = 0; i < per; i++) for (int b = 0; b < blocks; b++) out[k++] = dataBlocks[b][i];
        for (int i = 0; i < ec; i++) for (int b = 0; b < blocks; b++) out[k++] = ecBlocks[b][i];
        return out;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMul(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= gfMul(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    // ---------------------------------------------------------------- layout

    private void setFunction(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private void drawFunctionPatterns(int version) {
        for (int i = 0; i < size; i++) {
            setFunction(6, i, i % 2 == 0);
            setFunction(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);
        if (version >= 2) drawAlignment(ALIGN[version], ALIGN[version]);
        drawFormatBits(); // reserve the format areas; real bits are written after masking
    }

    private void drawFinder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) setFunction(x, y, dist != 2 && dist != 4);
            }
        }
    }

    private void drawAlignment(int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                setFunction(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    private void drawFormatBits() {
        int data = (0b00 << 3) | 0;     // EC level M = 00, mask 0
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) setFunction(8, i, bit(bits, i));
        setFunction(8, 7, bit(bits, 6));
        setFunction(8, 8, bit(bits, 7));
        setFunction(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFunction(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setFunction(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFunction(8, size - 15 + i, bit(bits, i));
        setFunction(8, size - 8, true);
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask0() {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!isFunction[y][x] && (x + y) % 2 == 0) modules[y][x] = !modules[y][x];
            }
        }
    }

    private static final class BitBuffer {
        private final java.util.BitSet bits = new java.util.BitSet();
        int length;

        void append(int value, int count) {
            for (int i = count - 1; i >= 0; i--) bits.set(length++, ((value >>> i) & 1) != 0);
        }

        int byteAt(int index) {
            int v = 0;
            for (int i = 0; i < 8; i++) v = (v << 1) | (bits.get(index * 8 + i) ? 1 : 0);
            return v;
        }
    }
}
