package gallery;

import gallery.catalog.Catalog;
import gallery.ui.GalleryFrame;

import javax.swing.*;

public final class Main {
    public static void main(String[] args) {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "Corel Gallery");
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
            Catalog catalog = new Catalog();
            catalog.load();
            GalleryFrame frame = new GalleryFrame(catalog);
            frame.setVisible(true);
            frame.loadAppIcon();
        });
    }
}
