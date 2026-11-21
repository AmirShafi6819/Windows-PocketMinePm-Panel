package com.pmpanel.app;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.InputStream;

/**
 * Windows replacement for the Android foreground service notification
 * (ServerService): a system tray icon that keeps the panel alive in the
 * background while a server is running, with a stop-all shortcut - the
 * desktop equivalent of the "server running" notification with its Stop action.
 */
public final class TrayService {

    private static volatile TrayIcon icon;
    private static volatile String lastState = "";

    private TrayService() {
    }

    public static synchronized void start() {
        if (!SystemTray.isSupported() || icon != null) return;
        try {
            Image img = loadIcon();
            TrayIcon ti = new TrayIcon(img, "PM Panel");
            ti.setImageAutoSize(true);
            PopupMenu menu = new PopupMenu();

            MenuItem open = new MenuItem("Open PM Panel");
            open.addActionListener(e -> DesktopApp.showWindow());
            menu.add(open);

            MenuItem state = new MenuItem("No server running");
            state.setEnabled(false);
            menu.add(state);

            MenuItem stopAll = new MenuItem("Stop all servers");
            stopAll.addActionListener(e -> new Thread(ServerManager::stopAll, "pm-tray-stopall").start());
            menu.add(stopAll);
            menu.addSeparator();
            MenuItem exit = new MenuItem("Exit (stops the servers)");
            exit.addActionListener(e -> {
                new Thread(ServerManager::stopAll, "pm-tray-exit-stop").start();
                DesktopApp.exitApplication();
            });
            menu.add(exit);

            ti.setPopupMenu(menu);
            ti.addActionListener(e -> DesktopApp.showWindow());
            SystemTray.getSystemTray().add(ti);
            icon = ti;
        } catch (Throwable t) {
            // tray is a nicety - never let it break the panel
        }
    }

    /** Mirror of ServerService's notification text refresh (running server list). */
    public static synchronized void updateState(String text) {
        lastState = text;
        TrayIcon ti = icon;
        if (ti == null) return;
        try {
            ti.setToolTip("PM Panel - " + text);
            // keep the disabled "state" item in sync
            if (ti.getPopupMenu() != null && ti.getPopupMenu().getItemCount() > 1) {
                MenuItem it = ti.getPopupMenu().getItem(1);
                if (it != null) it.setLabel(text);
            }
        } catch (Throwable ignored) {
        }
    }

    public static String stateText() {
        return lastState;
    }

    public static synchronized void stop() {
        TrayIcon ti = icon;
        icon = null;
        if (ti != null) {
            try {
                SystemTray.getSystemTray().remove(ti);
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean running() {
        return icon != null;
    }

    /** Show a transient balloon (used on hide-to-tray, like a heads-up notification). */
    public static void notify(String title, String text) {
        TrayIcon ti = icon;
        if (ti == null) return;
        try {
            ti.displayMessage(title, text, TrayIcon.MessageType.INFO);
        } catch (Throwable ignored) {
        }
    }

    private static Image loadIcon() {
        // prefer the bundled PNG icon from the app resources
        for (String res : new String[]{"/icons/pmpanel-64.png", "/icons/pmpanel-256.png"}) {
            try (InputStream in = TrayService.class.getResourceAsStream(res)) {
                if (in != null) {
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(in);
                    if (img != null) return img;
                }
            } catch (Throwable ignored) {
            }
        }
        return defaultImage();
    }

    private static Image defaultImage() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(64, 64,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(0x07, 0xF9, 0x17));
        g.fillRoundRect(2, 2, 60, 60, 16, 16);
        g.dispose();
        return img;
    }
}
