package com.custommenu.forge;

import com.custommenu.core.CustomMenuConfig;
import com.custommenu.core.MediaKind;
import com.custommenu.core.ScreenSettings;
import com.custommenu.core.ScreenType;
import com.custommenu.core.VideoDecoder;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;

@Mod("custommenu")
public class CustomMenuMod {
    public static final CustomMenuConfig CONFIG = new CustomMenuConfig(FMLPaths.CONFIGDIR.get());
    public static final AudioPlayer AUDIO = new AudioPlayer();

    private static ResourceLocation tex;
    private static DynamicTexture dyn;
    private static int texW = 0;
    private static int texH = 0;
    private static VideoDecoder video;
    private static VideoDecoder.Frame lastUploadedFrame;
    private static String loadedPath = "";

    public static ScreenSettings activeMedia(ScreenType type) {
        if (!CONFIG.enabled) return null;
        ScreenSettings s = CONFIG.get(type);
        if (s == null || !s.enabled) return null;
        return s;
    }

    public static void updateAudioFor(ScreenSettings s) {
        if (s == null || !s.playSound) {
            AUDIO.stop();
            return;
        }
        String targetAudio = s.audioPath;
        if (s.useVideoAudio && s.mediaPath != null && s.mediaPath.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            targetAudio = s.mediaPath;
        }
        AUDIO.update(targetAudio, s.volume, s.loop);
    }

    public static boolean prepare(String path, boolean loop) {
        if (path == null || path.isEmpty()) return false;
        File file = new File(path);
        if (!file.isFile()) return false;

        if (!path.equals(loadedPath)) {
            stopMedia();
            loadedPath = path;
            MediaKind kind = MediaKind.detect(file.getName());
            if (kind == MediaKind.VIDEO) {
                video = new VideoDecoder(file, loop, 1280);
                Thread t = new Thread(video, "CustomMenu-Video");
                t.setDaemon(true);
                t.start();
            } else if (kind == MediaKind.IMAGE) {
                try (InputStream in = new FileInputStream(file)) {
                    NativeImage img = NativeImage.read(in);
                    uploadImage(img);
                } catch (Throwable ignored) {}
            }
        }

        if (video != null) {
            VideoDecoder.Frame frame = video.latest.get();
            if (frame != null && frame != lastUploadedFrame) {
                lastUploadedFrame = frame;
                ensureTexture(frame.w, frame.h);
                NativeImage img = dyn.getPixels();
                if (img != null) {
                    for (int y = 0; y < frame.h; y++) {
                        int row = y * frame.w;
                        for (int x = 0; x < frame.w; x++) {
                            img.setPixelRGBA(x, y, frame.abgr[row + x]);
                        }
                    }
                    dyn.upload();
                }
            }
        }
        return tex != null;
    }

    private static void ensureTexture(int w, int h) {
        if (dyn != null && texW == w && texH == h && tex != null) return;
        NativeImage img = new NativeImage(w, h, false);
        uploadImage(img);
    }

    private static void uploadImage(NativeImage img) {
        Minecraft mc = Minecraft.getInstance();
        if (dyn != null) {
            dyn.close();
        }
        texW = img.getWidth();
        texH = img.getHeight();
        dyn = new DynamicTexture(img);
        tex = mc.getTextureManager().register("custommenu_bg", dyn);
    }

    public static void renderMedia(GuiGraphics g, int screenW, int screenH) {
        if (tex != null && texW > 0 && texH > 0) {
            g.blit(tex, 0, 0, screenW, screenH, 0, 0, texW, texH, texW, texH);
        }
    }

    public static void stopMedia() {
        if (video != null) {
            video.stop = true;
            video = null;
        }
        lastUploadedFrame = null;
        loadedPath = "";
    }

    @Mod.EventBusSubscriber(modid = "custommenu", value = Dist.CLIENT)
    public static class Client {
        @SubscribeEvent
        public static void onInitPost(ScreenEvent.Init.Post event) {
            if (event.getScreen() instanceof TitleScreen) {
                event.addListener(Button.builder(Component.literal("Custom Menu"), b -> {
                    Minecraft.getInstance().setScreen(new CustomMenuScreen(event.getScreen()));
                }).bounds(6, 6, 86, 20).build());
            }
        }

        @SubscribeEvent
        public static void onRenderPre(ScreenEvent.Render.Pre event) {
            ScreenType type = detectType(event.getScreen());
            if (type == null) return;
            ScreenSettings s = activeMedia(type);
            if (s == null) return;
            updateAudioFor(s);
            if (s.showMedia && prepare(s.mediaPath, s.loop)) {
                renderMedia(event.getGuiGraphics(), event.getScreen().width, event.getScreen().height);
            }
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            if (detectType(mc.screen) == null) {
                AUDIO.stop();
                stopMedia();
            }
        }

        private static ScreenType detectType(net.minecraft.client.gui.screens.Screen screen) {
            if (screen instanceof TitleScreen) return ScreenType.MAINMENU;
            if (screen instanceof PauseScreen) return ScreenType.PAUSE;
            if (screen instanceof DeathScreen) return ScreenType.GAMEOVER;
            return null;
        }
    }
}
