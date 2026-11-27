package com.custommenu.forge;

import com.custommenu.core.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Oyun açılışında (Mojang yüklemesinden sonra) ana menüden önce gösterilen intro. Dokununca atlanır. */
public class IntroScreen extends Screen {
    private final Screen next;
    private final long openedAt = System.currentTimeMillis();
    private long shownAt = -1;
    private boolean closing;

    public IntroScreen(Screen next) { super(Component.literal("Intro")); this.next = next; }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0xFF000000);
        ScreenSettings s = CustomMenuMod.activeMedia(ScreenType.INTRO);
        long now = System.currentTimeMillis();
        if (s == null) { finish(); return; }
        if (CustomMenuMod.prepare(s.mediaPath, false, s.useVideoAudio && s.playSound)) {
            if (shownAt < 0) shownAt = now;
            ScreenSettings c = new ScreenSettings();
            c.fit = s.fit; c.posX = s.posX; c.posY = s.posY; c.loop = false;
            CustomMenuMod.drawMedia(g, c, width, height);
            if (CustomMenuMod.mediaFinished() || (!CustomMenuMod.isMotion() && now - shownAt > 4000)) finish();
        } else if (now - openedAt > 10000) finish(); // yüklenemezse takılma
        g.drawCenteredString(font, Component.literal("Atlamak için dokun"), width / 2, height - 14, 0x99FFFFFF);
    }
    private void finish() {
        if (closing) return;
        closing = true;
        AudioPlayer.stop();
        minecraft.execute(() -> minecraft.setScreen(next));
    }
    @Override public boolean mouseClicked(double x, double y, int b) { finish(); return true; }
    @Override public boolean keyPressed(int k, int s, int m) { finish(); return true; }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean isPauseScreen() { return false; }
}
