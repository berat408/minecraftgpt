package com.custommenu.core;

import org.jcodec.api.FrameGrab;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.SeekableDemuxerTrack;
import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

public class VideoDecoder implements Runnable {
    public static final class Frame {
        public final int w;
        public final int h;
        public final int[] abgr;

        public Frame(int w, int h, int[] abgr) {
            this.w = w;
            this.h = h;
            this.abgr = abgr;
        }
    }

    public final AtomicReference<Frame> latest = new AtomicReference<>();
    public volatile boolean finished = false;
    public volatile boolean failed = false;
    public volatile String error = "";
    public volatile double fpsInfo = 30.0;
    public volatile boolean stop = false;

    private final File file;
    private final boolean loop;
    private final int maxW;

    public VideoDecoder(File file, boolean loop, int maxW) {
        this.file = file;
        this.loop = loop;
        this.maxW = Math.min(maxW > 0 ? maxW : 1280, 1280);
    }

    @Override
    public void run() {
        while (!stop) {
            FileChannelWrapper ch = null;
            try {
                ch = NIOUtils.readableChannel(file);
                FrameGrab grab = FrameGrab.createFrameGrab(ch);
                SeekableDemuxerTrack vt = grab.getVideoTrack();
                double fps = 30.0;
                if (vt != null) {
                    DemuxerTrackMeta meta = vt.getMeta();
                    if (meta != null && meta.getTotalFrames() > 0 && meta.getTotalDuration() > 0) {
                        fps = meta.getTotalFrames() / meta.getTotalDuration();
                    }
                }
                if (fps < 1.0 || fps > 120.0) fps = 30.0;
                this.fpsInfo = fps;
                long frameStepNanos = (long) (1_000_000_000.0 / fps);
                long nextFrameTime = System.nanoTime();

                BufferedImage scaledBuf = null;
                Graphics2D g2 = null;
                int targetW = 0;
                int targetH = 0;

                while (!stop) {
                    Picture pic = grab.getNativeFrame();
                    if (pic == null) break;

                    BufferedImage src = AWTUtil.toBufferedImage(pic);
                    int sw = src.getWidth();
                    int sh = src.getHeight();
                    if (targetW == 0) {
                        if (sw > maxW) {
                            targetW = maxW;
                            targetH = Math.max(1, (int) Math.round((double) sh * maxW / sw));
                        } else {
                            targetW = sw;
                            targetH = sh;
                        }
                        scaledBuf = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
                        g2 = scaledBuf.createGraphics();
                        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    }

                    g2.drawImage(src, 0, 0, targetW, targetH, null);
                    int[] argb = scaledBuf.getRGB(0, 0, targetW, targetH, null, 0, targetW);
                    int[] abgr = new int[argb.length];
                    for (int i = 0; i < argb.length; i++) {
                        int c = argb[i];
                        int a = (c >>> 24) & 0xFF;
                        int r = (c >>> 16) & 0xFF;
                        int g = (c >>> 8) & 0xFF;
                        int b = c & 0xFF;
                        abgr[i] = (a << 24) | (b << 16) | (g << 8) | r;
                    }
                    latest.set(new Frame(targetW, targetH, abgr));

                    nextFrameTime += frameStepNanos;
                    long sleepNanos = nextFrameTime - System.nanoTime();
                    if (sleepNanos > 1_000_000L) {
                        Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
                    } else if (sleepNanos < -frameStepNanos * 3) {
                        nextFrameTime = System.nanoTime();
                    }
                }
                if (g2 != null) g2.dispose();
            } catch (Throwable t) {
                failed = true;
                error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                break;
            } finally {
                if (ch != null) {
                    try { ch.close(); } catch (Throwable ignored) {}
                }
            }
            if (!loop || stop) break;
        }
        finished = true;
    }
}
