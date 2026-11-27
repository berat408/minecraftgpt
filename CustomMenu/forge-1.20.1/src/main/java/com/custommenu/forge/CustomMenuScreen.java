package com.custommenu.forge;

import com.custommenu.core.ScreenType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class CustomMenuScreen extends Screen {
    private final Screen parent;

    public CustomMenuScreen(Screen parent) {
        super(Component.literal("Custom Menu"));
        this.parent = parent;
    }

    private static String onOff(boolean b) {
        return b ? "Açık" : "Kapalı";
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = 44;
        int w = 200;
        int h = 20;
        int step = 25;

        addRenderableWidget(Button.builder(Component.literal("Mod: " + onOff(CustomMenuMod.CONFIG.enabled)), b -> {
            CustomMenuMod.CONFIG.enabled = !CustomMenuMod.CONFIG.enabled;
            CustomMenuMod.CONFIG.save();
            rebuildWidgets();
        }).bounds(cx - w / 2, y, w, h).build());
        y += step;

        for (ScreenType t : ScreenType.values()) {
            addRenderableWidget(Button.builder(Component.literal("Ayarlar: " + t.folder), b -> {
                if (minecraft != null) {
                    minecraft.setScreen(new ScreenSettingsScreen(this, t));
                }
            }).bounds(cx - w / 2, y, w, h).build());
            y += step;
        }

        addRenderableWidget(Button.builder(Component.literal("Kapat"), b -> onClose())
                .bounds(cx - w / 2, y + 6, w, h).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
