package com.peripheral.workflow.label;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * QR Code modelo 2, ECC M, máscara 0. Suficiente para o payload da etiqueta.
 */
final class QrMatrix {

    private static final int[] TOTAL_CW = {0, 26, 44, 70, 100, 134, 172};
    private static final int[] DATA_CW = {0, 16, 28, 44, 64, 86, 108};
    private static final int[] EC_CW = {0, 10, 16, 26, 18, 24, 16};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4};
    private static final int[] ALIGN = {0, -1, 18, 22, 26, 30, 34};

    private QrMatrix() {
    }

    static boolean[][] encode(String text) {
        byte[] data = (text != null ? text : "").getBytes(StandardCharsets.UTF_8);
        int version = 1;
        while (version <= 6 && dataCodewordsNeeded(data.length) > DATA_CW[version]) {
            version++;
        }
        if (version > 6) {
            version = 6;
            int maxBytes = DATA_CW[6] - 3;
            if (data.length > maxBytes) {
                data = Arrays.copyOf(data, Math.max(1, maxBytes));
            }
        }
        return build(version, data);
    }

    static BufferedImage toImage(boolean[][] modules, int modulePx, int quiet) {
        int n = modules.length;
        int size = (n + quiet * 2) * modulePx;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);
        g.setColor(Color.BLACK);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (modules[y][x]) {
                    g.fillRect((x + quiet) * modulePx, (y + quiet) * modulePx, modulePx, modulePx);
                }
            }
        }
        g.dispose();
        return image;
    }

    private static int dataCodewordsNeeded(int byteLen) {
        return (4 + 8 + byteLen * 8 + 7) / 8 + 1;
    }

    private static boolean[][] build(int version, byte[] data) {
        int size = 21 + 4 * (version - 1);
        int[][] grid = new int[size][size]; // 0 empty, 1 black, 2 white reserved
        for (int i = 0; i < size; i++) {
            Arrays.fill(grid[i], 0);
        }
        placeFinders(grid, size);
        placeTiming(grid, size);
        placeAlignment(grid, version);
        placeDarkModule(grid, size);
        reserveFormat(grid, size);

        byte[] codewords = buildCodewords(version, data);
        placeData(grid, size, codewords);
        applyMask0(grid, size);
        placeFormat(grid, size);

        boolean[][] modules = new boolean[size][size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                modules[y][x] = grid[y][x] == 1;
            }
        }
        return modules;
    }

    private static void setReserved(int[][] grid, int x, int y, boolean black) {
        if (y < 0 || x < 0 || y >= grid.length || x >= grid.length) {
            return;
        }
        grid[y][x] = black ? 1 : 2;
    }

    private static void placeFinder(int[][] grid, int ox, int oy) {
        for (int y = -1; y <= 7; y++) {
            for (int x = -1; x <= 7; x++) {
                boolean in = x >= 0 && x <= 6 && y >= 0 && y <= 6;
                boolean black = in && (x == 0 || x == 6 || y == 0 || y == 6
                        || (x >= 2 && x <= 4 && y >= 2 && y <= 4));
                if (in || (x >= -1 && x <= 7 && y >= -1 && y <= 7)) {
                    setReserved(grid, ox + x, oy + y, black && in);
                    if (!in) {
                        setReserved(grid, ox + x, oy + y, false);
                    }
                }
            }
        }
    }

    private static void placeFinders(int[][] grid, int size) {
        placeFinder(grid, 0, 0);
        placeFinder(grid, size - 7, 0);
        placeFinder(grid, 0, size - 7);
    }

    private static void placeTiming(int[][] grid, int size) {
        for (int i = 8; i < size - 8; i++) {
            if (grid[6][i] == 0) {
                setReserved(grid, i, 6, i % 2 == 0);
            }
            if (grid[i][6] == 0) {
                setReserved(grid, 6, i, i % 2 == 0);
            }
        }
    }

    private static void placeAlignment(int[][] grid, int version) {
        int p = ALIGN[version];
        if (p < 0) {
            return;
        }
        int ox = p - 2;
        int oy = p - 2;
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 5; x++) {
                boolean black = x == 0 || x == 4 || y == 0 || y == 4 || (x == 2 && y == 2);
                setReserved(grid, ox + x, oy + y, black);
            }
        }
    }

    private static void placeDarkModule(int[][] grid, int size) {
        setReserved(grid, 8, size - 8, true);
    }

    private static void reserveFormat(int[][] grid, int size) {
        for (int i = 0; i < 9; i++) {
            if (grid[8][i] == 0) {
                grid[8][i] = 2;
            }
            if (grid[i][8] == 0) {
                grid[i][8] = 2;
            }
        }
        for (int i = 0; i < 8; i++) {
            if (grid[8][size - 1 - i] == 0) {
                grid[8][size - 1 - i] = 2;
            }
            if (grid[size - 1 - i][8] == 0) {
                grid[size - 1 - i][8] = 2;
            }
        }
    }

    private static void placeFormat(int[][] grid, int size) {
        // ECC M (00) + mask 0 (000) → após BCH e XOR 0x5412
        int bits = 0x5412;
        int[] pos = new int[]{
                0, 1, 2, 3, 4, 5, 7, 8
        };
        for (int i = 0; i < 8; i++) {
            boolean black = ((bits >> (14 - i)) & 1) == 1;
            grid[8][pos[i] == 6 ? 7 : pos[i]] = black ? 1 : 2;
            // horizontal near finder: 0-5,7,8 maps to cols 0,1,2,3,4,5,7,8
        }
        // Rewrite with explicit ISO placement
        int[] horiz = {0, 1, 2, 3, 4, 5, 7, 8};
        int[] vert = {0, 1, 2, 3, 4, 5, 7, 8};
        for (int i = 0; i < 8; i++) {
            boolean black = ((bits >> (14 - i)) & 1) == 1;
            grid[8][horiz[i]] = black ? 1 : 2;
        }
        for (int i = 0; i < 7; i++) {
            boolean black = ((bits >> (6 - i)) & 1) == 1;
            grid[vert[i]][8] = black ? 1 : 2;
        }
        // second copy
        for (int i = 0; i < 8; i++) {
            boolean black = ((bits >> i) & 1) == 1;
            grid[8][size - 1 - i] = black ? 1 : 2;
        }
        for (int i = 0; i < 7; i++) {
            boolean black = ((bits >> (14 - i)) & 1) == 1;
            grid[size - 1 - i][8] = black ? 1 : 2;
        }
        // Fix remaining format bits on left finder vertical 14..8
        boolean[] fmt = new boolean[15];
        for (int i = 0; i < 15; i++) {
            fmt[i] = ((bits >> (14 - i)) & 1) == 1;
        }
        grid[8][0] = fmt[0] ? 1 : 2;
        grid[8][1] = fmt[1] ? 1 : 2;
        grid[8][2] = fmt[2] ? 1 : 2;
        grid[8][3] = fmt[3] ? 1 : 2;
        grid[8][4] = fmt[4] ? 1 : 2;
        grid[8][5] = fmt[5] ? 1 : 2;
        grid[8][7] = fmt[6] ? 1 : 2;
        grid[8][8] = fmt[7] ? 1 : 2;
        grid[7][8] = fmt[8] ? 1 : 2;
        grid[5][8] = fmt[9] ? 1 : 2;
        grid[4][8] = fmt[10] ? 1 : 2;
        grid[3][8] = fmt[11] ? 1 : 2;
        grid[2][8] = fmt[12] ? 1 : 2;
        grid[1][8] = fmt[13] ? 1 : 2;
        grid[0][8] = fmt[14] ? 1 : 2;
        grid[size - 1][8] = fmt[0] ? 1 : 2;
        grid[size - 2][8] = fmt[1] ? 1 : 2;
        grid[size - 3][8] = fmt[2] ? 1 : 2;
        grid[size - 4][8] = fmt[3] ? 1 : 2;
        grid[size - 5][8] = fmt[4] ? 1 : 2;
        grid[size - 6][8] = fmt[5] ? 1 : 2;
        grid[size - 7][8] = fmt[6] ? 1 : 2;
        grid[8][size - 8] = fmt[7] ? 1 : 2;
        grid[8][size - 7] = fmt[8] ? 1 : 2;
        grid[8][size - 6] = fmt[9] ? 1 : 2;
        grid[8][size - 5] = fmt[10] ? 1 : 2;
        grid[8][size - 4] = fmt[11] ? 1 : 2;
        grid[8][size - 3] = fmt[12] ? 1 : 2;
        grid[8][size - 2] = fmt[13] ? 1 : 2;
        grid[8][size - 1] = fmt[14] ? 1 : 2;
    }

    private static byte[] buildCodewords(int version, byte[] data) {
        int dataCw = DATA_CW[version];
        BitBuffer bits = new BitBuffer();
        bits.append(4, 4); // byte mode
        bits.append(data.length, 8);
        for (byte b : data) {
            bits.append(b & 0xFF, 8);
        }
        int capacityBits = dataCw * 8;
        int remain = capacityBits - bits.size;
        if (remain > 4) {
            bits.append(0, 4);
        } else if (remain > 0) {
            bits.append(0, remain);
        }
        while (bits.size % 8 != 0) {
            bits.append(0, 1);
        }
        int pad = 0xEC;
        while (bits.size / 8 < dataCw) {
            bits.append(pad, 8);
            pad = pad == 0xEC ? 0x11 : 0xEC;
        }
        byte[] dataCodewords = bits.toBytes(dataCw);
        return addEcc(version, dataCodewords);
    }

    private static byte[] addEcc(int version, byte[] data) {
        int blockCount = BLOCKS[version];
        int ecPerBlock = EC_CW[version];
        int dataPerBlock = DATA_CW[version] / blockCount;
        int shortBlocks = blockCount * dataPerBlock - DATA_CW[version];
        // V4/V5/V6: some versions have two group sizes; keep equal split for V4-V6 M.
        byte[][] blocks = new byte[blockCount][];
        byte[][] ecc = new byte[blockCount][];
        int offset = 0;
        int[] gen = rsGenerator(ecPerBlock);
        for (int i = 0; i < blockCount; i++) {
            int len = dataPerBlock;
            if (version >= 4 && i >= blockCount - Math.abs(shortBlocks)) {
                // equal blocks for our tables (DATA_CW divisible by BLOCKS)
            }
            blocks[i] = Arrays.copyOfRange(data, offset, offset + len);
            offset += len;
            ecc[i] = rsEncode(blocks[i], gen, ecPerBlock);
        }
        int total = TOTAL_CW[version];
        byte[] out = new byte[total];
        int pos = 0;
        int maxData = dataPerBlock;
        for (int i = 0; i < maxData; i++) {
            for (int b = 0; b < blockCount; b++) {
                if (i < blocks[b].length) {
                    out[pos++] = blocks[b][i];
                }
            }
        }
        for (int i = 0; i < ecPerBlock; i++) {
            for (int b = 0; b < blockCount; b++) {
                out[pos++] = ecc[b][i];
            }
        }
        return out;
    }

    private static void placeData(int[][] grid, int size, byte[] codewords) {
        int bitIndex = 0;
        int totalBits = codewords.length * 8;
        boolean upward = true;
        for (int col = size - 1; col > 0; col -= 2) {
            if (col == 6) {
                col--;
            }
            for (int i = 0; i < size; i++) {
                int y = upward ? size - 1 - i : i;
                for (int dx = 0; dx < 2; dx++) {
                    int x = col - dx;
                    if (grid[y][x] != 0) {
                        continue;
                    }
                    boolean black = false;
                    if (bitIndex < totalBits) {
                        int cw = codewords[bitIndex / 8] & 0xFF;
                        black = ((cw >> (7 - (bitIndex % 8))) & 1) == 1;
                        bitIndex++;
                    }
                    grid[y][x] = black ? 1 : 3; // 3 = white data
                }
            }
            upward = !upward;
        }
    }

    private static void applyMask0(int[][] grid, int size) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int v = grid[y][x];
                if (v != 1 && v != 3) {
                    continue;
                }
                boolean black = v == 1;
                if ((x + y) % 2 == 0) {
                    black = !black;
                }
                grid[y][x] = black ? 1 : 2;
            }
        }
    }

    private static int[] rsGenerator(int degree) {
        int[] gen = new int[]{1};
        for (int i = 0; i < degree; i++) {
            gen = rsMul(gen, new int[]{1, gfPow(2, i)});
        }
        return gen;
    }

    private static byte[] rsEncode(byte[] data, int[] gen, int ec) {
        int[] result = new int[data.length + ec];
        for (int i = 0; i < data.length; i++) {
            result[i] = data[i] & 0xFF;
        }
        for (int i = 0; i < data.length; i++) {
            int coef = result[i];
            if (coef == 0) {
                continue;
            }
            for (int j = 0; j < gen.length; j++) {
                result[i + j] ^= gfMul(gen[j], coef);
            }
        }
        byte[] out = new byte[ec];
        for (int i = 0; i < ec; i++) {
            out[i] = (byte) result[data.length + i];
        }
        return out;
    }

    private static int[] rsMul(int[] a, int[] b) {
        int[] r = new int[a.length + b.length - 1];
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < b.length; j++) {
                r[i + j] ^= gfMul(a[i], b[j]);
            }
        }
        return r;
    }

    private static int gfMul(int a, int b) {
        int p = 0;
        for (int i = 0; i < 8; i++) {
            if ((b & 1) != 0) {
                p ^= a;
            }
            boolean hi = (a & 0x80) != 0;
            a = (a << 1) & 0xFF;
            if (hi) {
                a ^= 0x1D;
            }
            b >>= 1;
        }
        return p;
    }

    private static int gfPow(int x, int exp) {
        int r = 1;
        for (int i = 0; i < exp; i++) {
            r = gfMul(r, x);
        }
        return r;
    }

    private static final class BitBuffer {
        private final int[] bits = new int[2048];
        private int size;

        void append(int value, int len) {
            for (int i = len - 1; i >= 0; i--) {
                bits[size++] = (value >> i) & 1;
            }
        }

        byte[] toBytes(int count) {
            byte[] out = new byte[count];
            for (int i = 0; i < count * 8 && i < size; i++) {
                if (bits[i] != 0) {
                    out[i / 8] |= (byte) (1 << (7 - (i % 8)));
                }
            }
            return out;
        }
    }
}
