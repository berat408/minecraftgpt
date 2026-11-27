package com.custommenu.core;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

public class CustomMenuConfig {
    public boolean enabled = true;
    public final Map<ScreenType, ScreenSettings> screens = new EnumMap<>(ScreenType.class);
    public final Path dir;

    public CustomMenuConfig(Path configDir) {
        this.dir = configDir.resolve("custommenu");
        reset();
        load();
    }

    public void reset() {
        enabled = true;
        for (ScreenType t : ScreenType.values()) {
            screens.put(t, new ScreenSettings());
        }
    }

    public ScreenSettings get(ScreenType t) {
        return screens.computeIfAbsent(t, k -> new ScreenSettings());
    }

    public void load() {
        try {
            Files.createDirectories(dir);
            for (ScreenType t : ScreenType.values()) {
                Files.createDirectories(dir.resolve(t.folder));
            }
            Path file = dir.resolve("settings.properties");
            if (!Files.exists(file)) {
                save();
                return;
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
            enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
            for (ScreenType t : ScreenType.values()) {
                ScreenSettings s = get(t);
                String k = t.folder + ".";
                s.enabled = Boolean.parseBoolean(p.getProperty(k + "enabled", "true"));
                s.showMedia = Boolean.parseBoolean(p.getProperty(k + "showMedia", "true"));
                s.playSound = Boolean.parseBoolean(p.getProperty(k + "playSound", "true"));
                s.useVideoAudio = Boolean.parseBoolean(p.getProperty(k + "useVideoAudio", "false"));
                s.loop = Boolean.parseBoolean(p.getProperty(k + "loop", "true"));
                s.mediaPath = p.getProperty(k + "mediaPath", "");
                s.audioPath = p.getProperty(k + "audioPath", "");
                s.volume = Float.parseFloat(p.getProperty(k + "volume", "1.0"));
                s.fit = Integer.parseInt(p.getProperty(k + "fit", "0"));
                s.posX = Integer.parseInt(p.getProperty(k + "posX", "0"));
                s.posY = Integer.parseInt(p.getProperty(k + "posY", "0"));
            }
        } catch (Throwable ignored) {
        }
    }

    public void save() {
        try {
            Files.createDirectories(dir);
            Properties p = new Properties();
            p.setProperty("enabled", Boolean.toString(enabled));
            for (ScreenType t : ScreenType.values()) {
                ScreenSettings s = get(t);
                String k = t.folder + ".";
                p.setProperty(k + "enabled", Boolean.toString(s.enabled));
                p.setProperty(k + "showMedia", Boolean.toString(s.showMedia));
                p.setProperty(k + "playSound", Boolean.toString(s.playSound));
                p.setProperty(k + "useVideoAudio", Boolean.toString(s.useVideoAudio));
                p.setProperty(k + "loop", Boolean.toString(s.loop));
                p.setProperty(k + "mediaPath", s.mediaPath == null ? "" : s.mediaPath);
                p.setProperty(k + "audioPath", s.audioPath == null ? "" : s.audioPath);
                p.setProperty(k + "volume", Float.toString(s.volume));
                p.setProperty(k + "fit", Integer.toString(s.fit));
                p.setProperty(k + "posX", Integer.toString(s.posX));
                p.setProperty(k + "posY", Integer.toString(s.posY));
            }
            try (OutputStream out = Files.newOutputStream(dir.resolve("settings.properties"))) {
                p.store(out, "CustomMenu Settings");
            }
        } catch (Throwable ignored) {
        }
    }
}
