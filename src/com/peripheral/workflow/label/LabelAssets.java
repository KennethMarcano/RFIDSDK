package com.peripheral.workflow.label;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.URL;

final class LabelAssets {

    private static final String[] CLASSPATH_PATHS = {
            "/resources/images.png",
            "resources/images.png",
            "/images.png",
            "images.png"
    };

    private static final String[] FILESYSTEM_PATHS = {
            "src/resources/images.png",
            "resources/images.png"
    };

    private static volatile BufferedImage cachedLogo;

    private LabelAssets() {
    }

    static BufferedImage loadLogo() {
        BufferedImage cached = cachedLogo;
        if (cached != null) {
            return cached;
        }
        BufferedImage loaded = cropToContent(readLogo());
        if (loaded != null) {
            cachedLogo = loaded;
        }
        return loaded;
    }

    private static BufferedImage readLogo() {
        for (String path : CLASSPATH_PATHS) {
            URL url = LabelAssets.class.getResource(path);
            if (url == null) {
                url = LabelAssets.class.getClassLoader().getResource(path);
            }
            if (url != null) {
                try (InputStream in = url.openStream()) {
                    BufferedImage image = ImageIO.read(in);
                    if (isValid(image)) {
                        return image;
                    }
                } catch (Exception ignored) {
                    // tenta o próximo caminho
                }
            }
        }
        for (String path : FILESYSTEM_PATHS) {
            File file = new File(path);
            if (!file.isFile()) {
                continue;
            }
            try {
                BufferedImage image = ImageIO.read(file);
                if (isValid(image)) {
                    return image;
                }
            } catch (Exception ignored) {
                // tenta o próximo caminho
            }
        }
        return null;
    }

    private static boolean isValid(BufferedImage image) {
        return image != null && image.getWidth() > 0 && image.getHeight() > 0;
    }

    private static BufferedImage cropToContent(BufferedImage source) {
        if (!isValid(source)) {
            return source;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!isLogoInk(source.getRGB(x, y))) {
                    continue;
                }
                if (x < minX) {
                    minX = x;
                }
                if (y < minY) {
                    minY = y;
                }
                if (x > maxX) {
                    maxX = x;
                }
                if (y > maxY) {
                    maxY = y;
                }
            }
        }
        if (maxX < minX) {
            return source;
        }
        int pad = Math.max(2, Math.min(width, height) / 50);
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(width - 1, maxX + pad);
        maxY = Math.min(height - 1, maxY + pad);
        int cropW = maxX - minX + 1;
        int cropH = maxY - minY + 1;
        BufferedImage cropped = new BufferedImage(cropW, cropH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = cropped.createGraphics();
        g.drawImage(source, 0, 0, cropW, cropH, minX, minY, maxX + 1, maxY + 1, null);
        g.dispose();
        return cropped;
    }

    private static boolean isLogoInk(int argb) {
        int alpha = (argb >> 24) & 0xFF;
        if (alpha < 40) {
            return false;
        }
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        float[] hsb = Color.RGBtoHSB(r, g, b, null);
        int luminance = (r * 30 + g * 59 + b * 11) / 100;
        return hsb[1] > 0.18f || luminance < 200;
    }
}
