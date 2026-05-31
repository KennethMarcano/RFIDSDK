package com.peripheral.app;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import java.awt.Image;
import java.io.File;
import java.net.URL;

public final class BrandingAssets {

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

    private BrandingAssets() {
    }

    public static JLabel createEshipLogoLabel(int maxSize) {
        ImageIcon icon = loadEshipLogoIcon(maxSize);
        if (icon == null) {
            JLabel fallback = new JLabel("eship");
            fallback.setFont(fallback.getFont().deriveFont(java.awt.Font.BOLD, 18f));
            return fallback;
        }
        return new JLabel(icon);
    }

    public static ImageIcon loadEshipLogoIcon(int maxSize) {
        ImageIcon raw = loadRawIcon();
        if (raw == null || raw.getIconWidth() <= 0) {
            return null;
        }
        Image scaled = raw.getImage().getScaledInstance(maxSize, maxSize, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }

    private static ImageIcon loadRawIcon() {
        for (String path : CLASSPATH_PATHS) {
            URL url = BrandingAssets.class.getResource(path);
            if (url == null) {
                url = BrandingAssets.class.getClassLoader().getResource(path);
            }
            if (url != null) {
                return new ImageIcon(url);
            }
        }
        for (String path : FILESYSTEM_PATHS) {
            File file = new File(path);
            if (file.isFile()) {
                return new ImageIcon(file.getAbsolutePath());
            }
        }
        return null;
    }
}
