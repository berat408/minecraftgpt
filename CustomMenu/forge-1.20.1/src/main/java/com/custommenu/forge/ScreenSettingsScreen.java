package com.custommenu.forge;

import com.custommenu.core.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.File;

/** Tek bir ekranın (intro/ana menü/ESC/game over) ayarları. */
public class ScreenSettingsScreen extends Screen {
    private final Screen parent;
    private final ScreenType type;
    private final ScreenSettings s;

    public ScreenSettingsScreen(Screen parent, ScreenType type) {
        super(Component.literal("Custom Menu - " + type.folder));
        this.parent = parent; this.type = type; this.s = CustomMenuMod.CONFIG.get(type);
    }
    private static String onOff(boolean b) { return b ? "Açık" : "Kapalı"; }
    private void apply() { CustomMenuMod.applyAndSave(); rebuildWidgets(); }

    @Override protected void init() {
        int lx = width / 2 - 155, rx = width / 2 + 5, w = 150, y = 34, step = 24;
        addRenderableWidget(Button.builder(Component.literal("Ekran: " + onOff(s.enabled)), b -> { s.enabled = !s.enabled; apply(); }).bounds(lx, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Görüntü: " + onOff(s.showMedia)), b -> { s.showMedia = !s.showMedia; apply(); }).bounds(lx, y + step, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Ses: " + onOff(s.playSound)), b -> { s.playSound = !s.playSound; apply(); }).bounds(lx, y + step * 2, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Döngü: " + onOff(s.loop)), b -> { s.loop = !s.loop; apply(); }).bounds(lx, y + step * 3, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Sığdırma: " + s.fit), b -> {
            s.fit = s.fit.equals("fit") ? "fill" : s.fit.equals("fill") ? "stretch" : "fit"; apply();
        }).bounds(lx, y + step * 4, w, 20).build());

        addRenderableWidget(new AbstractSliderButton(rx, y, w, 20, Component.literal("Ses seviyesi: " + Math.round(s.volume * 100) + "%"), s.volume) {
            @Override protected void updateMessage() { setMessage(Component.literal("Ses seviyesi: " + Math.round(value * 100) + "%")); }
            @Override protected void applyValue() { s.volume = (float) value; CustomMenuMod.CONFIG.save(); AudioPlayer.setVolume(s.volume); }
        });
        addRenderableWidget(Button.builder(Component.literal("Görsel/video seç"), b ->
                minecraft.setScreen(new FileBrowserScreen(this, type, false, f -> { s.mediaPath = f.getAbsolutePath(); CustomMenuMod.applyAndSave(); }))
        ).bounds(rx, y + step, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Müzik seç"), b ->
                minecraft.setScreen(new FileBrowserScreen(this, type, true, f -> { s.audioPath = f.getAbsolutePath(); CustomMenuMod.applyAndSave(); }))
        ).bounds(rx, y + step * 2, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Dosyaları kaldır"), b -> { s.mediaPath = ""; s.audioPath = ""; apply(); })
                .bounds(rx, y + step * 3, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Bu ekranı sıfırla"), b -> {
            CustomMenuMod.CONFIG.screens.put(type, new ScreenSettings()); CustomMenuMod.applyAndSave(); minecraft.setScreen(new ScreenSettingsScreen(parent, type));
        }).bounds(rx, y + step * 4, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal(AudioPlayer.isActive(audioSrc()) ? "Müziği durdur" : "Müzik dene"), b -> {
            String src = audioSrc();
            if (src.isEmpty()) { AudioPlayer.status = "Önce müzik seç"; return; }
            if (AudioPlayer.isActive(src)) AudioPlayer.stop(); else AudioPlayer.update(src, s.volume, s.loop);
            rebuildWidgets();
        }).bounds(lx, y + step * 5, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Video sesi: " + onOff(s.useVideoAudio)), b -> { s.useVideoAudio = !s.useVideoAudio; apply(); })
                .bounds(rx, y + step * 5, w, 20).build());
        if (type == ScreenType.INTRO)
            addRenderableWidget(Button.builder(Component.literal("Önizle"), b -> minecraft.setScreen(new IntroScreen(this)))
                    .bounds(width / 2 - 75, y + step * 6, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Bitti"), b -> onClose())
                .bounds(width / 2 - 75, height - 28, 150, 20).build());
    }
    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        super.render(g, mx, my, pt);
        int y = 34 + 24 * 6 + 6 + (type == ScreenType.INTRO ? 24 : 0);
        g.drawCenteredString(font, Component.literal("Görsel: " + name(s.mediaPath)), width / 2, y, 0xAAAAAA);
        g.drawCenteredString(font, Component.literal("Müzik: " + name(s.audioPath)), width / 2, y + 12, 0xAAAAAA);
        g.drawCenteredString(font, Component.literal(font.plainSubstrByWidth("Görsel: " + CustomMenuMod.mediaStatus + "  |  " + AudioPlayer.status, width - 16)), width / 2, y + 24, 0x88CCFF);
    }
    private String audioSrc() {
        boolean vid = s.useVideoAudio && !s.mediaPath.isEmpty() && MediaKind.detect(s.mediaPath) == MediaKind.VIDEO;
        return vid ? s.mediaPath : s.audioPath;
    }
    private String name(String p) {
        if (p.isEmpty()) return "(yok)";
        String n = new File(p).getName();
        return font.plainSubstrByWidth(n, width - 80);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
