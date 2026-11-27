package com.custommenu.core;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class CustomMenuConfig {
    public boolean enabled = false;      // mod varsayılan kapalı
    public final Map<ScreenType, ScreenSettings> screens = new EnumMap<>(ScreenType.class);
    private final Path dir;
    public CustomMenuConfig(Path configDir) {
        this.dir = configDir.resolve("custommenu");
        reset();
    }
    public Path dir() { return dir; }
    public void reset() {
        enabled = false;
        for (ScreenType t : ScreenType.values()) screens.put(t, new ScreenSettings());
    }
    public ScreenSettings get(ScreenType t) { return screens.get(t); }

    public void load() {
        try {
            Path f = dir.resolve("settings.properties");
            if (!Files.exists(f)) return;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(f)) { p.load(in); }
            enabled = Boolean.parseBoolean(p.getProperty("enabled", "false"));
            for (ScreenType t : ScreenType.values()) {
                ScreenSettings s = get(t); String k = t.folder + ".";
                s.enabled = Boolean.parseBoolean(p.getProperty(k + "enabled", "false"));
                s.showMedia = Boolean.parseBoolean(p.getProperty(k + "showMedia", "false"));
                s.playSound = Boolean.parseBoolean(p.getProperty(k + "playSound", "false"));
                s.loop = Boolean.parseBoolean(p.getProperty(k + "loop", "true"));
                s.useVideoAudio = Boolean.parseBoolean(p.getProperty(k + "useVideoAudio", "false"));
                s.mediaPath = p.getProperty(k + "mediaPath", "");
                s.audioPath = p.getProperty(k + "audioPath", "");
                s.fit = p.getProperty(k + "fit", "fit");
                s.volume = parse(p.getProperty(k + "volume"), 1f);
                s.posX = parse(p.getProperty(k + "posX"), 0.5f);
                s.posY = parse(p.getProperty(k + "posY"), 0.5f);
            }
        } catch (Throwable ignored) { /* bozuk ayar dosyası oyunu çökertmesin */ }
    }
    public void save() {
        try {
            Files.createDirectories(dir);
            Properties p = new Properties();
            p.setProperty("enabled", String.valueOf(enabled));
            for (ScreenType t : ScreenType.values()) {
                ScreenSettings s = get(t); String k = t.folder + ".";
                Files.createDirectories(dir.resolve(t.folder));
                p.setProperty(k + "enabled", String.valueOf(s.enabled));
                p.setProperty(k + "showMedia", String.valueOf(s.showMedia));
                p.setProperty(k + "playSound", String.valueOf(s.playSound));
                p.setProperty(k + "loop", String.valueOf(s.loop));
                p.setProperty(k + "useVideoAudio", String.valueOf(s.useVideoAudio));
                p.setProperty(k + "mediaPath", s.mediaPath);
                p.setProperty(k + "audioPath", s.audioPath);
                p.setProperty(k + "fit", s.fit);
                p.setProperty(k + "volume", String.valueOf(s.volume));
                p.setProperty(k + "posX", String.valueOf(s.posX));
                p.setProperty(k + "posY", String.valueOf(s.posY));
            }
            try (OutputStream out = Files.newOutputStream(dir.resolve("settings.properties"))) {
                p.store(out, "Custom Menu");
            }
        } catch (Throwable ignored) { }
    }
    private static float parse(String v, float d) {
        try { return v == null ? d : Float.parseFloat(v); } catch (Exception e) { return d; }
    }
}
