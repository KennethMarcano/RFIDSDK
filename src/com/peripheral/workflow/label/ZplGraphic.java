package com.peripheral.workflow.label;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converte o logo colorido em gráfico ZPL binário. Amarelo e azul-marinho
 * viram preto; fundo claro permanece branco (impressora térmica).
 */
final class ZplGraphic {

    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<String, String>();

    private ZplGraphic() {
    }

    static String toGfa(BufferedImage source, int widthDots, int heightDots) {
        if (source == null || widthDots < 8 || heightDots < 8) {
            return "";
        }
        String key = widthDots + "x" + heightDots + "@" + source.getWidth() + "x" + source.getHeight();
        String cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String encoded = encode(toBinary(source, widthDots, heightDots));
        CACHE.put(key, encoded);
        return encoded;
    }

    private static BufferedImage toBinary(BufferedImage source, int widthDots, int heightDots) {
        BufferedImage scaled = new BufferedImage(widthDots, heightDots, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, widthDots, heightDots);
        double scale = Math.min(widthDots / (double) source.getWidth(),
                heightDots / (double) source.getHeight());
        int drawW = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int drawH = Math.max(1, (int) Math.round(source.getHeight() * scale));
        int ox = (widthDots - drawW) / 2;
        int oy = (heightDots - drawH) / 2;
        g.drawImage(source, ox, oy, drawW, drawH, null);
        g.dispose();

        BufferedImage binary = new BufferedImage(widthDots, heightDots, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < heightDots; y++) {
            for (int x = 0; x < widthDots; x++) {
                binary.setRGB(x, y, isPrintBlack(scaled.getRGB(x, y)) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        return binary;
    }

    static boolean isPrintBlack(int argb) {
        int alpha = (argb >> 24) & 0xFF;
        if (alpha < 40) {
            return false;
        }
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        float[] hsb = Color.RGBtoHSB(r, g, b, null);
        int luminance = (r * 30 + g * 59 + b * 11) / 100;
        return hsb[1] > 0.22f || luminance < 150;
    }

    private static String encode(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int rowBytes = (width + 7) / 8;
        int totalBytes = rowBytes * height;
        StringBuilder hex = new StringBuilder(totalBytes * 2);
        for (int y = 0; y < height; y++) {
            for (int xByte = 0; xByte < rowBytes; xByte++) {
                int value = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int x = xByte * 8 + bit;
                    if (x < width && isBlackPixel(image.getRGB(x, y))) {
                        value |= 1 << (7 - bit);
                    }
                }
                hex.append(String.format("%02X", value));
            }
        }
        return "^GFA," + totalBytes + "," + totalBytes + "," + rowBytes + "," + hex;
    }

    private static boolean isBlackPixel(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r < 128 && g < 128 && b < 128;
    }
}
