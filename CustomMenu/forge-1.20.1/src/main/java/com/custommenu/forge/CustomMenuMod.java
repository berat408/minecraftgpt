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
import org.lwjgl.system.MemoryUtil;
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

    // ---- tek aktif medya oturumu: görsel/GIF/video + (AudioPlayer'da) ses ----
    private static final AtomicReference<byte[]> pendingPng = new AtomicReference<>();
    private static final AtomicReference<GifAnim> pendingGif = new AtomicReference<>();
    private static ResourceLocation tex; private static int texW, texH;
    private static DynamicTexture dyn; private static GifAnim anim; private static long animStart; private static int shownFrame = -1;
    private static VideoDecoder video; private static long videoStartedAt; private static int lastLoops;
    private static String loadedPath = ""; private static volatile boolean loading;
    private static boolean introHandled = false;
    private static Screen sessionOwner;   // oturumun ait olduğu ekran; ekran değişince oturum yeniden başlar
    private static int idleTicks = 0;
    private static GuiGraphics panGraphics; // her karede yeniden oluşturulmasın

    public CustomMenuMod() {
        CONFIG.load();
        CONFIG.save();
        if (FMLEnvironment.dist == Dist.CLIENT) MinecraftForge.EVENT_BUS.register(new Client());
    }

    public static void applyAndSave() { CONFIG.save(); reload(); }

    /** Ayar değişti: her şeyi bırak, yeniden başlatmadan baştan uygula. */
    public static void reload() {
        releaseTexture(); stopVideo();
        loadedPath = ""; mediaStatus = "";
        pendingPng.set(null); pendingGif.set(null);
        AudioPlayer.stop(); AudioPlayer.clearCache();
        CONFIG.load();
    }

    private static void releaseTexture() {
        try { if (tex != null) Minecraft.getInstance().getTextureManager().release(tex); } catch (Throwable ignored) { }
        tex = null; dyn = null; anim = null; shownFrame = -1;
    }
    private static void stopVideo() { if (video != null) { video.stop(); video = null; } }
    private static void fail(String what, Throwable t) { LOG.warn("CustomMenu: " + what, t); mediaStatus = what + ": " + t; }

    private static boolean isOwnScreen(Screen sc) {
        return sc instanceof CustomMenuScreen || sc instanceof ScreenSettingsScreen || sc instanceof FileBrowserScreen;
    }

    /**
     * Ekran (menü) değiştiğinde eski oturumu temiz kapatır: video oynatıcı durur, ses durur, video dokusu bırakılır.
     * Yeni ekrana girince video ve ses sıfırdan, birlikte başlar. Ayar ekranlarımız oturumu bozmaz.
     */
    static void syncScreen(Screen sc) {
        if (isOwnScreen(sc) || sc == sessionOwner) return;
        boolean hadVideo = video != null;
        stopVideo();
        AudioPlayer.stop();
        if (hadVideo) { releaseTexture(); loadedPath = ""; }          // video yeniden çözülür (baştan)
        else if (anim != null) { animStart = System.currentTimeMillis(); shownFrame = -1; } // GIF baştan
        sessionOwner = sc;                                            // durağan resim: doku korunur, anında görünür
    }

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

    private static Field ptrField; private static boolean ptrTried;

    /** NativeImage'in iç piksel adresini bulur (getPointer() bu sürümde yok). Bulunamazsa 0 döner. */
    private static long pixelAddress(NativeImage px, int w, int h) {
        try {
            if (!ptrTried) {
                ptrTried = true;
                long size = (long) w * h * 4; // 'size' alanı bu değere eşit, adres alanı eşit değil
                for (Field f : NativeImage.class.getDeclaredFields()) {
                    if (f.getType() == long.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        if (f.getLong(px) != size) { ptrField = f; break; }
                    }
                }
                if (ptrField == null) LOG.warn("CustomMenu: piksel adresi bulunamadı, yavaş kopyalama kullanılacak");
            }
            return ptrField == null ? 0L : ptrField.getLong(px);
        } catch (Throwable t) { ptrField = null; return 0L; }
    }

    /** Pikselleri doğrudan belleğe kopyalar (hızlı); olmazsa piksel piksel yazar (yavaş ama kesin). */
    private static void copyPixels(NativeImage px, int[] data, int w, int h) {
        long addr = pixelAddress(px, w, h);
        if (addr != 0L) {
            try { MemoryUtil.memIntBuffer(addr, w * h).put(data, 0, w * h); return; }
            catch (Throwable t) { ptrField = null; }
        }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px.setPixelRGBA(x, y, data[y * w + x]);
    }

    /** Ayarlara göre hazırla: ses de çalacaksa video ses hazır olana kadar bekler, birlikte başlarlar. */
    static boolean prepareFor(ScreenSettings s, boolean loop) {
        boolean sync = s.playSound && (s.useVideoAudio || !s.audioPath.isEmpty());
        return prepare(s.mediaPath, loop, sync, s.useVideoAudio && sync);
    }

    /** Görseli/GIF'i/videoyu (gerekirse) yükler; çizilebilir hale geldiyse true. Render thread'den çağrılmalı. */
    static boolean prepare(String path, boolean loop, boolean syncAudio, boolean rewindOnLoop) {
        Minecraft mc = Minecraft.getInstance();
        syncScreen(mc.screen);
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
        if (v != null) {
            if (!v.gate && (AudioPlayer.isPlaying() || AudioPlayer.hasFailed() || System.currentTimeMillis() - videoStartedAt > 20000)) v.gate = true;
            int lc = v.loops;
            if (lc != lastLoops) { lastLoops = lc; if (rewindOnLoop && lc > 0) AudioPlayer.rewind(); } // video döngüsüyle ses baştan
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
                    v.recycle(f.abgr); // diziyi havuza geri ver
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
                video.gate = !syncAudio; videoStartedAt = System.currentTimeMillis(); lastLoops = 0;
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
                if (s != null && prepareFor(s, s.loop)) {
                    Minecraft mc = Minecraft.getInstance();
                    if (panGraphics == null) panGraphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
                    GuiGraphics g = panGraphics;
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
                if (s == null || !prepareFor(s, s.loop)) return;
                drawMedia(e.getGuiGraphics(), s, e.getScreen().width, e.getScreen().height);
            } catch (Throwable t) { LOG.warn("CustomMenu: ESC çizim hatası", t); }
        }

        /** Bitiş/kredi ekranı kendi opak arka planını çiziyor; bu yüzden görsel yazıların üstüne yarı saydam çizilir. */
        @SubscribeEvent
        public void onRenderPost(ScreenEvent.Render.Post e) {
            try {
                if (!(e.getScreen() instanceof WinScreen)) return;
                ScreenSettings s = activeMedia(ScreenType.GAMEOVER);
                if (s == null || !prepareFor(s, s.loop)) return;
                GuiGraphics g = e.getGuiGraphics();
                g.setColor(1f, 1f, 1f, 0.45f);
                drawMedia(g, s, e.getScreen().width, e.getScreen().height);
                g.setColor(1f, 1f, 1f, 1f);
            } catch (Throwable t) { LOG.warn("CustomMenu: bitiş ekranı çizim hatası", t); }
        }

        /** Ekran değişimini izler; müzik: ilgili ekran açıkken çal, ekran kapanınca durdur. */
        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Screen cur = Minecraft.getInstance().screen;
            syncScreen(cur); // ekran değiştiyse eski oturumu bitir (video+ses durur)
            if (cur == null) { if (++idleTicks > 1200) AudioPlayer.clearCache(); } else idleTicks = 0; // oyundayken ~1 dk sonra ses önbelleğini boşalt
            if (isOwnScreen(cur)) return; // ayarlarda müzik sürsün/test edilebilsin
            if (cur instanceof IntroScreen && !((IntroScreen) cur).isSettled()) return; // intro açılışında ses de biraz sonra başlar
            String path = ""; float vol = 1f; boolean loop = true;
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
