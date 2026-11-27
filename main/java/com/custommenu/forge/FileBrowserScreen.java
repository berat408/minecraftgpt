package com.custommenu.forge;

import com.custommenu.core.MediaKind;
import com.custommenu.core.MediaScanner;
import com.custommenu.core.ScreenType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class FileBrowserScreen extends Screen {
    private final Screen parent;
    private final ScreenType type;
    private final boolean audioOnly;
    private final Consumer<File> onPick;
    private final List<File> entries = new ArrayList<>();

    public FileBrowserScreen(Screen parent, ScreenType type, boolean audioOnly, Consumer<File> onPick) {
        super(Component.literal(audioOnly ? "Müzik seç" : "Görsel / video seç"));
        this.parent = parent;
        this.type = type;
        this.audioOnly = audioOnly;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        entries.clear();
        File folder = CustomMenuMod.CONFIG.dir.resolve(type.folder).toFile();
        for (File f : MediaScanner.scan(folder)) {
            MediaKind k = MediaKind.detect(f.getName());
            if (audioOnly && k == MediaKind.AUDIO) entries.add(f);
            if (!audioOnly && (k == MediaKind.IMAGE || k == MediaKind.GIF || k == MediaKind.VIDEO)) entries.add(f);
        }

        int cx = this.width / 2;
        int y = 40;
        int w = 240;

        addRenderableWidget(Button.builder(Component.literal("[Temizle / Seçimi Kaldır]"), b -> {
            onPick.accept(null);
            onClose();
        }).bounds(cx - w / 2, y, w, 20).build());
        y += 24;

        for (File f : entries) {
            if (y > this.height - 40) break;
            addRenderableWidget(Button.builder(Component.literal(f.getName()), b -> {
                onPick.accept(f);
                onClose();
            }).bounds(cx - w / 2, y, w, 20).build());
            y += 22;
        }

        addRenderableWidget(Button.builder(Component.literal("İptal"), b -> onClose())
                .bounds(cx - 60, this.height - 28, 120, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
