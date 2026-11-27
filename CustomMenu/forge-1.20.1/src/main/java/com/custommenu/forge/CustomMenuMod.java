package com.custommenu.forge;

import com.custommenu.core.*;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

@Mod("custommenu")
public class CustomMenuMod {
    static final Logger LOG = LogUtils.getLogger();
    public static final CustomMenuConfig CONFIG = new CustomMenuConfig(FMLPaths.CONFIGDIR.get());
    public static volatile String mediaStatus = "";

    // ---- görsel durumu ----
    private static final AtomicReference<byte[]> pendingPng = new AtomicReference<>();
    private static final AtomicReference<GifAnim> pendingGif = new AtomicReference<>();
    private static ResourceLocation tex; private static int texW, texH;
    private static DynamicTexture dyn; private static GifAnim anim; private static long animStart; private static int shownFrame = -1;
    private static VideoDecoder video;
    private static String loadedPath = ""; private static volatile boolean loading;
    private static boolean introHandled = false;

    public CustomMenuMod() {
        CONFIG.load();
        CONFIG.save();
        if (FMLEnvironment.dist == Dist.CLIENT) MinecraftForge.EVENT_BUS.register(new Client());
    }

    public static void applyAndSave() { CONFIG.save(); reload(); }

    /** Yeniden başlatmadan uygula. */
    public static void reload() {
        releaseTexture(); stopVideo();
        loadedPath = ""; mediaStatus = "";
        pendingPng.set(null); pendingGif.set(null);
        AudioPlayer.stop();
        CONFIG.load();
    }

    /** Pikselleri doğrudan belleğe kopyalar (piksel piksel yazmaktan çok daha hızlı). */
    private static void copyPixels(NativeImage px, int[] data, int w, int h) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                px.setPixelRGBA(x, y, data[row + x]);
            }
        }
    }

    private static void releaseTexture() {
        try { if (tex != null) Minecraft.getInstance().getTextureManager().release(tex); } catch (Throwable ignored) { }
        tex = null; dyn = null; anim = null; shownFrame = -1;
    }
    private static void stopVideo() { if (video != null) { video.stop(); video = null; } }
    private static void fail(String what, Throwable t) { LOG.warn("CustomMenu: " + what, t); mediaStatus = what + ": " + t; }

    static ScreenType typeOf(Screen sc) {
        if (sc instanceof IntroScreen) return ScreenType.INTRO;
        if (sc instanceof TitleScreen) return ScreenType.MAINMENU;
        if (sc instanceof PauseScreen) return ScreenType.PAUSE;
        if (sc instanceof WinScreen) return ScreenType.GAMEOVER; // Ender ejderhası sonrası bitiş/kredi ekranı
        return null;
    }

    /** Bu ekran için görsel aktifse ayarlarını döndürür, değilse null. */
    static ScreenSettings activeMedia(ScreenType t) {
        if (t == null || !CONFIG.enabled) return null;
        ScreenSettings s = CONFIG.get(t);
        return (s.enabled && s.showMedia && !s.mediaPath.isEmpty()) ? s : null;
    }

    static boolean isMotion() { return video != null || anim != null; }
    static boolean mediaFinished() {
        if (video != null) return video.finished || video.failed;
        if (anim != null) return System.currentTimeMillis() - animStart >= anim.total;
        return false;
    }

    /** Görseli/GIF'i/videoyu (gerekirse) yükler; çizilebilir hale geldiyse true. Render thread'den çağrılmalı. */
    private static long videoStartedAt;
    static boolean prepare(String path, boolean loop) { return prepare(path, loop, false); }

    /** syncAudio: videonun sesi çalacaksa, video ses hazır olana kadar başlamaz. */
    static boolean prepare(String path, boolean loop, boolean syncAudio) {
        Minecraft mc = Minecraft.getInstance();
        byte[] png = pendingPng.getAndSet(null);
        if (png != null) {
            try {
                NativeImage img = NativeImage.read(new ByteArrayInputStream(png));
                texW = img.getWidth(); texH = img.getHeight(); anim = null; dyn = null;
                DynamicTexture dt = new DynamicTexture(img);
                dt.setFilter(true, false); // yumuşak ölçekleme (kalite)
                tex = mc.getTextureManager().register("custommenu_img", dt);
                mediaStatus = "Görsel hazır (" + texW + "x" + texH + ")";
            } catch (Throwable t) { fail("Görsel hatası", t); }
        }
        GifAnim g = pendingGif.getAndSet(null);
        if (g != null) {
            try {
                dyn = new DynamicTexture(g.w, g.h, false);
                dyn.setFilter(true, false);
                texW = g.w; texH = g.h; anim = g; animStart = System.currentTimeMillis(); shownFrame = -1;
                tex = mc.getTextureManager().register("custommenu_img", dyn);
                mediaStatus = "GIF hazır (" + g.frames.length + " kare)";
            } catch (Throwable t) { fail("GIF hatası", t); }
        }
        VideoDecoder v = video;
        if (v != null && !v.gate && (AudioPlayer.isPlaying() || AudioPlayer.hasFailed() || System.currentTimeMillis() - videoStartedAt > 20000)) v.gate = true;
        if (v != null) {
            VideoDecoder.Frame f = v.latest.getAndSet(null);
            if (f != null) {
                try {
                    if (dyn == null || texW != f.w || texH != f.h) {
                        dyn = new DynamicTexture(f.w, f.h, false);
                        dyn.setFilter(true, false);
                        texW = f.w; texH = f.h; anim = null;
                        tex = mc.getTextureManager().register("custommenu_img", dyn);
                    }
                    NativeImage px = dyn.getPixels();
                    if (px != null) { copyPixels(px, f.abgr, f.w, f.h); dyn.upload(); }
                    mediaStatus = "Video oynuyor (" + f.w + "x" + f.h + ", " + Math.round(v.fpsInfo) + " fps)";
                } catch (Throwable t) { fail("Video hatası", t); }
            }
            if (v.failed) { mediaStatus = "Video hatası: " + v.error; }
        }
        if (!path.equals(loadedPath) && !loading) {
            loadedPath = path;
            releaseTexture(); stopVideo();
            File file = new File(path);
            if (!file.isFile()) { mediaStatus = "Dosya bulunamadı"; return false; }
            MediaKind kind = MediaKind.detect(file.getName());
            mediaStatus = "Yükleniyor...";
            if (kind == MediaKind.VIDEO) {
                video = new VideoDecoder(file, loop, 0); // orijinal çözünürlük
                video.gate = !syncAudio; videoStartedAt = System.currentTimeMillis();
                Thread t = new Thread(video, "CustomMenu-Video"); t.setDaemon(true); t.start();
            } else {
                loading = true;
                Thread t = new Thread(() -> {
                    try {
                        if (kind == MediaKind.GIF) pendingGif.set(GifAnim.load(file, 720, 64_000_000L));
                        else {
                            BufferedImage bi = ImageIO.read(file);
                            if (bi == null) throw new IllegalStateException("Görsel okunamadı");
                            int big = Math.max(bi.getWidth(), bi.getHeight());
                            if (big > 2560) { // çok büyük fotoğrafları hafif küçült (bellek)
                                float sc = 2560f / big;
                                BufferedImage o = new BufferedImage(Math.round(bi.getWidth() * sc), Math.round(bi.getHeight() * sc), BufferedImage.TYPE_INT_ARGB);
                                Graphics2D gg = o.createGraphics();
                                gg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                                gg.drawImage(bi, 0, 0, o.getWidth(), o.getHeight(), null); gg.dispose();
                                bi = o;
                            }
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            ImageIO.write(bi, "png", bo);
                            pendingPng.set(bo.toByteArray());
                        }
                    } catch (Throwable ex) { fail("Görsel yüklenemedi", ex); }
                    loading = false;
                }, "CustomMenu-Loader");
                t.setDaemon(true); t.start();
            }
        }
        return tex != null;
    }

    static void drawMedia(GuiGraphics g, ScreenSettings s, int sw, int sh) {
        if (tex == null) return;
        if (anim != null && dyn != null) {
            int idx = anim.frameAt(System.currentTimeMillis() - animStart, s.loop);
            if (idx != shownFrame) {
                NativeImage px = dyn.getPixels();
                int[] fr = anim.frames[idx];
                if (px != null) { copyPixels(px, fr, anim.w, anim.h); dyn.upload(); }
                shownFrame = idx;
            }
        }
        float scale = s.fit.equals("fill") ? Math.max((float) sw / texW, (float) sh / texH)
                : s.fit.equals("stretch") ? -1 : Math.min((float) sw / texW, (float) sh / texH);
        int w = scale < 0 ? sw : Math.round(texW * scale), h = scale < 0 ? sh : Math.round(texH * scale);
        int x = Math.round((sw - w) * s.posX), y = Math.round((sh - h) * s.posY);
        g.blit(tex, x, y, w, h, 0, 0, texW, texH, texW, texH);
    }

    /** Ana menüdeki panorama çizicisinin yerine geçer; görsel hazırsa onu çizer, değilse normal panorama. */
    static class MediaPanorama extends PanoramaRenderer {
        private final PanoramaRenderer orig;
        MediaPanorama(PanoramaRenderer orig) { super(TitleScreen.CUBE_MAP); this.orig = orig; }
        @Override public void render(float partialTick, float alpha) {
            try {
                ScreenSettings s = activeMedia(ScreenType.MAINMENU);
                if (s != null && prepare(s.mediaPath, s.loop, s.useVideoAudio && s.playSound)) {
                    Minecraft mc = Minecraft.getInstance();
                    GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
                    drawMedia(g, s, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                    g.flush();
                    return;
                }
            } catch (Throwable t) { LOG.warn("CustomMenu: ana menü çizim hatası", t); }
            orig.render(partialTick, alpha);
        }
    }

    static class Client {
        @SubscribeEvent
        public void onOpening(ScreenEvent.Opening e) { // intro: oyun açılışında ana menüden önce
            if (!introHandled && e.getNewScreen() instanceof TitleScreen) {
                introHandled = true;
                if (activeMedia(ScreenType.INTRO) != null) e.setNewScreen(new IntroScreen(e.getNewScreen()));
            }
        }

        @SubscribeEvent
        public void onInit(ScreenEvent.Init.Post e) {
            Screen s = e.getScreen();
            if (s instanceof TitleScreen || s instanceof PauseScreen)
                e.addListener(Button.builder(Component.literal("Custom Menu"),
                        b -> Minecraft.getInstance().setScreen(new CustomMenuScreen(s))).bounds(4, 4, 90, 20).build());
            if (s instanceof TitleScreen) {
                try {
                    boolean found = false;
                    for (Field f : TitleScreen.class.getDeclaredFields()) {
                        if (f.getType() == PanoramaRenderer.class) {
                            f.setAccessible(true);
                            Object cur = f.get(s);
                            if (!(cur instanceof MediaPanorama)) f.set(s, new MediaPanorama((PanoramaRenderer) cur));
                            found = true;
                        }
                    }
                    if (!found) LOG.warn("CustomMenu: panorama alanı bulunamadı, ana menü görseli çizilemez");
                } catch (Throwable t) { LOG.warn("CustomMenu: panorama değiştirilemedi", t); }
            }
        }

        /** ESC menüsü: ekranın arkasına çiz. */
        @SubscribeEvent
        public void onRender(ScreenEvent.Render.Pre e) {
            try {
                if (!(e.getScreen() instanceof PauseScreen)) return;
                ScreenSettings s = activeMedia(ScreenType.PAUSE);
                if (s == null || !prepare(s.mediaPath, s.loop, s.useVideoAudio && s.playSound)) return;
                drawMedia(e.getGuiGraphics(), s, e.getScreen().width, e.getScreen().height);
            } catch (Throwable t) { LOG.warn("CustomMenu: ESC çizim hatası", t); }
        }

        /** Bitiş/kredi ekranı kendi opak arka planını çiziyor; bu yüzden görsel yazıların üstüne yarı saydam çizilir. */
        @SubscribeEvent
        public void onRenderPost(ScreenEvent.Render.Post e) {
            try {
                if (!(e.getScreen() instanceof WinScreen)) return;
                ScreenSettings s = activeMedia(ScreenType.GAMEOVER);
                if (s == null || !prepare(s.mediaPath, s.loop, s.useVideoAudio && s.playSound)) return;
                GuiGraphics g = e.getGuiGraphics();
                g.setColor(1f, 1f, 1f, 0.45f);
                drawMedia(g, s, e.getScreen().width, e.getScreen().height);
                g.setColor(1f, 1f, 1f, 1f);
            } catch (Throwable t) { LOG.warn("CustomMenu: bitiş ekranı çizim hatası", t); }
        }

        /** Müzik: ilgili ekran açıkken çal, ekran kapanınca durdur. */
        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            String path = ""; float vol = 1f; boolean loop = true;
            Screen cur = Minecraft.getInstance().screen;
            if (cur instanceof CustomMenuScreen || cur instanceof ScreenSettingsScreen || cur instanceof FileBrowserScreen) return; // ayarlarda müzik sürsün/test edilebilsin
            ScreenType t = typeOf(cur);
            if (t != null && CONFIG.enabled) {
                ScreenSettings s = CONFIG.get(t);
                if (s.enabled && s.playSound) {
                    boolean vid = s.useVideoAudio && !s.mediaPath.isEmpty() && MediaKind.detect(s.mediaPath) == MediaKind.VIDEO;
                    if (vid) path = s.mediaPath; else if (!s.audioPath.isEmpty()) path = s.audioPath;
                    vol = s.volume; loop = s.loop && t != ScreenType.INTRO;
                }
            }
            AudioPlayer.update(path, vol, loop);
        }
    }
}
