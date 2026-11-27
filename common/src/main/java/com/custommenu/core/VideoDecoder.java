package com.custommenu.core;

import org.jcodec.api.FrameGrab;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicReference;

/** MP4'ü (H.264) arka planda çözer. Orijinal çözünürlük/FPS. Kareler zaman damgasına göre sıralanır, tempo zaman damgasıyla tutulur. */
public class VideoDecoder implements Runnable {
    public static final class Frame {
        public final int w, h; public final int[] abgr;
        Frame(int w, int h, int[] p) { this.w = w; this.h = h; this.abgr = p; }
    }
    private static final class Item { final double ts; final Picture pic; Item(double t, Picture p) { ts = t; pic = p; } }
    private static final int REORDER = 4; // B-kareler için sıralama tamponu

    public final AtomicReference<Frame> latest = new AtomicReference<>();
    public volatile boolean finished, failed;
    public volatile String error = "";
    public volatile double fpsInfo = 0;
    public volatile boolean gate = true; // false ise ses hazır olana kadar oynatma başlamaz
    private volatile boolean stop;
    private final File file; private final boolean loop; private final int maxW; // maxW <= 0: küçültme yok

    public VideoDecoder(File file, boolean loop, int maxW) { this.file = file; this.loop = loop; this.maxW = maxW; }
    public void stop() { stop = true; }

    @Override public void run() {
        try {
            do { playOnce(); } while (loop && !stop);
            finished = true;
        } catch (Throwable t) {
            failed = true; error = String.valueOf(t);
        }
    }

    private void playOnce() throws Exception {
        try (FileChannelWrapper ch = NIOUtils.readableChannel(file)) {
            FrameGrab grab = FrameGrab.createFrameGrab(ch);
            double fps = 25;
            try {
                DemuxerTrackMeta m = grab.getVideoTrack().getMeta();
                if (m.getTotalDuration() > 0 && m.getTotalFrames() > 0) fps = m.getTotalFrames() / m.getTotalDuration();
            } catch (Throwable ignored) { }
            if (fps < 1 || fps > 120) fps = 25;
            fpsInfo = fps;
            PriorityQueue<Item> q = new PriorityQueue<>((a, b) -> Double.compare(a.ts, b.ts));
            boolean useMeta = true, tsOk = true;
            long n = 0; double minTs = 1e18, maxTs = -1e18;
            long[] st = {-1, 0}; double[] ts0 = {0}; // st[0]=başlangıç ns, st[1]=son gösterilen ns
            boolean any = false;
            while (!stop) {
                Picture pic = null; double ts = 0; boolean eof = false;
                if (useMeta) {
                    try {
                        Object pm = Refl.call(grab, "getNativeFrameWithMetadata");
                        if (pm == null) eof = true;
                        else { pic = (Picture) Refl.call(pm, "getPicture"); ts = ((Number) Refl.call(pm, "getTimestamp")).doubleValue(); }
                    } catch (NoSuchMethodException e) { useMeta = false; continue; }
                }
                if (!useMeta) { pic = grab.getNativeFrame(); if (pic == null) eof = true; }
                if (!eof) {
                    if (pic == null) { eof = true; }
                    else {
                        n++; any = true;
                        if (!useMeta) ts = n / fps;
                        else {
                            minTs = Math.min(minTs, ts); maxTs = Math.max(maxTs, ts);
                            if (n == 6 && maxTs - minTs <= 0) tsOk = false;
                            if (!tsOk) ts = n / fps;
                        }
                        q.add(new Item(ts, pic.cloneCropped()));
                    }
                }
                while (!q.isEmpty() && (eof || q.size() > REORDER) && !stop) show(q.poll(), fps, st, ts0);
                if (eof) break;
            }
            if (!any) throw new IllegalStateException("Video karesi okunamadı (desteklenmeyen kodek?)");
        }
    }

    private void show(Item it, double fps, long[] st, double[] ts0) throws Exception {
        if (st[0] < 0) { // oynatmayı (gerekirse sesi bekleyerek) başlat
            while (!gate && !stop) Thread.sleep(10);
            st[0] = System.nanoTime(); ts0[0] = it.ts;
        }
        long frameNs = (long) (1e9 / fps);
        long due = st[0] + (long) ((it.ts - ts0[0]) * 1e9), now = System.nanoTime();
        boolean late = now - due > frameNs;
        if (!late || now - st[1] > 120_000_000L) { // geride kalınca dönüştürmeyi atla, en az ~8 kare/sn göster
            latest.set(convert(AWTUtil.toBufferedImage(it.pic)));
            st[1] = System.nanoTime();
            long wait = (due - st[1]) / 1_000_000L;
            if (wait > 0) Thread.sleep(wait);
        }
    }

    private Frame convert(BufferedImage bi) {
        int W = bi.getWidth(), H = bi.getHeight();
        boolean scale = maxW > 0 && W > maxW;
        if (!scale && bi.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            byte[] d = ((DataBufferByte) bi.getRaster().getDataBuffer()).getData();
            if (d.length == W * H * 3) { // hızlı yol: ham B,G,R baytları
                int[] px = new int[W * H];
                for (int i = 0, k = 0; i < px.length; i++, k += 3)
                    px[i] = 0xFF000000 | ((d[k] & 0xFF) << 16) | ((d[k + 1] & 0xFF) << 8) | (d[k + 2] & 0xFF);
                return new Frame(W, H, px);
            }
        }
        float sc = scale ? (float) maxW / W : 1f;
        int ow = Math.max(1, Math.round(W * sc)), oh = Math.max(1, Math.round(H * sc));
        BufferedImage out = bi;
        if (ow != W || oh != H) {
            out = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(bi, 0, 0, ow, oh, null); g.dispose();
        }
        int[] px = out.getRGB(0, 0, ow, oh, null, 0, ow);
        for (int k = 0; k < px.length; k++) {
            int p = px[k];
            px[k] = 0xFF000000 | ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF);
        }
        return new Frame(ow, oh, px);
    }
}
