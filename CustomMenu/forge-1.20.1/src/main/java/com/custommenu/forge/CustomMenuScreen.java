package com.custommenu.forge;

import com.custommenu.core.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Ana ayar sayfası: mod aç/kapat + 4 ekranın ayar sayfalarına giriş. */
public class CustomMenuScreen extends Screen {
    private final Screen parent;
    public CustomMenuScreen(Screen parent) { super(Component.literal("Custom Menu")); this.parent = parent; }

    private static String onOff(boolean b) { return b ? "Açık" : "Kapalı"; }

    @Override protected void init() {
        int x = width / 2 - 100, y = 36;
        addRenderableWidget(Button.builder(Component.literal("Mod: " + onOff(CustomMenuMod.CONFIG.enabled)), b -> {
            CustomMenuMod.CONFIG.enabled = !CustomMenuMod.CONFIG.enabled;
            CustomMenuMod.applyAndSave(); rebuildWidgets();
        }).bounds(x, y, 200, 20).build());
        String[] names = {"Intro ekranı", "Ana menü", "Duraklatma menüsü (ESC)", "Bitiş / kredi ekranı"};
        for (ScreenType t : ScreenType.values()) {
            y += 26;
            ScreenSettings s = CustomMenuMod.CONFIG.get(t);
            addRenderableWidget(Button.builder(Component.literal(names[t.ordinal()] + ": " + onOff(s.enabled)),
                    b -> minecraft.setScreen(new ScreenSettingsScreen(this, t))).bounds(x, y, 200, 20).build());
        }
        y += 26;
        addRenderableWidget(Button.builder(Component.literal("Varsayılana sıfırla"), b -> {
            CustomMenuMod.CONFIG.reset(); CustomMenuMod.applyAndSave(); rebuildWidgets();
        }).bounds(x, y, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Bitti"), b -> onClose())
                .bounds(x, Math.max(y + 26, height - 28), 200, 20).build());
    }
    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        g.drawCenteredString(font, Component.literal("sürüm 1.3.0 (oturum + pack.mcmeta)"), width / 2, 25, 0x777777);
        super.render(g, mx, my, pt);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
