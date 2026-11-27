package com.custommenu.forge;

import com.custommenu.core.ScreenSettings;
import com.custommenu.core.ScreenType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ScreenSettingsScreen extends Screen {
    private final Screen parent;
    private final ScreenType type;
    private final ScreenSettings s;

    public ScreenSettingsScreen(Screen parent, ScreenType type) {
        super(Component.literal("Custom Menu - " + type.folder));
        this.parent = parent;
        this.type = type;
        this.s = CustomMenuMod.CONFIG.get(type);
    }

    private static String onOff(boolean b) {
        return b ? "Açık" : "Kapalı";
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = 36;
        int w = 220;
        int h = 20;
        int step = 24;

        addRenderableWidget(Button.builder(Component.literal("Ekran Etkin: " + onOff(s.enabled)), b -> {
            s.enabled = !s.enabled;
            CustomMenuMod.CONFIG.save();
            rebuildWidgets();
        }).bounds(cx - w / 2, y, w, h).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("Medya Göster: " + onOff(s.showMedia)), b -> {
            s.showMedia = !s.showMedia;
            CustomMenuMod.CONFIG.save();
            rebuildWidgets();
        }).bounds(cx - w / 2, y, w, h).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("Ses Çal: " + onOff(s.playSound)), b -> {
            s.playSound = !s.playSound;
            CustomMenuMod.CONFIG.save();
            rebuildWidgets();
        }).bounds(cx - w / 2, y, w, h).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("Videonun Sesini Uygula: " + onOff(s.useVideoAudio)), b -> {
            s.useVideoAudio = !s.useVideoAudio;
            CustomMenuMod.CONFIG.save();
            rebuildWidgets();
        }).bounds(cx - w / 2, y, w, h).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("Döngü (Loop): " + onOff(s.loop)), b -> {
            s.loop = !s.loop;
            CustomMenuMod.CONFIG.save();
            rebuildWidgets();
        }).bounds(cx - w / 2, y, w, h).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("Görsel / Video Seç"), b -> {
            if (minecraft != null) {
                minecraft.setScreen(new FileBrowserScreen(this, type, false, file -> {
                    s.mediaPath = file == null ? "" : file.getAbsolutePath();
                    CustomMenuMod.CONFIG.save();
                }));
            }
        }).bounds(cx - w / 2, y, w, h).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("Müzik Seç (MP3)"), b -> {
            if (minecraft != null) {
                minecraft.setScreen(new FileBrowserScreen(this, type, true, file -> {
                    s.audioPath = file == null ? "" : file.getAbsolutePath();
                    CustomMenuMod.CONFIG.save();
                }));
            }
        }).bounds(cx - w / 2, y, w, h).build());
        y += step;

        addRenderableWidget(new AbstractSliderButton(cx - w / 2, y, w, h,
                Component.literal("Ses seviyesi: " + Math.round(s.volume * 100) + "%"), s.volume) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal("Ses seviyesi: " + Math.round(value * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                s.volume = (float) value;
                CustomMenuMod.CONFIG.save();
            }
        });
        y += step + 4;

        addRenderableWidget(Button.builder(Component.literal("Geri"), b -> onClose())
                .bounds(cx - w / 2, y, w, h).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
