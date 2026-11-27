package com.custommenu.core;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.BooleanSupplier;

/** MP4/M4A dosyasındaki AAC ses kanalını PCM'e çevirir (JCodec, yansıma ile). */
public final class AacExtractor {
    public static final class Pcm { public short[] data; public int rate = 44100, ch = 2; }
    private AacExtractor() { }

    public static Pcm decode(File f, int maxShorts, BooleanSupplier cancelled) throws Exception {
        Object chan = Refl.callStatic("org.jcodec.common.io.NIOUtils", "readableChannel", f);
        try {
            Object dem = Refl.callStatic("org.jcodec.containers.mp4.demuxer.MP4Demuxer", "createMP4Demuxer", chan);
            List<?> tracks = (List<?>) Refl.call(dem, "getAudioTracks");
            if (tracks == null || tracks.isEmpty()) throw new IllegalStateException("Dosyada ses kanalı yok");
            Object tr = tracks.get(0);
            Object[] entries = (Object[]) Refl.call(tr, "getSampleEntries");
            Object ase = entries[0];
            Class<?> esdsCls = Class.forName("org.jcodec.containers.mp4.boxes.EsdsBox");
            Object esds = null;
            for (String holder : new String[]{"org.jcodec.containers.mp4.boxes.NodeBox", "org.jcodec.containers.mp4.boxes.Box"}) {
                if (esds != null) break;
                try { esds = Refl.callStatic(holder, "findFirst", ase, esdsCls, new String[]{"esds"}); } catch (Throwable ignored) { }
            }
            if (esds == null) throw new IllegalStateException("AAC ayar kutusu bulunamadı (desteklenmeyen ses kodeği)");
            Object dec = Refl.newInstance("org.jcodec.codecs.aac.AACDecoder", Refl.call(esds, "getStreamInfo"));

            Pcm out = new Pcm();
            short[] buf = new short[1 << 20]; int len = 0;
            while (!cancelled.getAsBoolean() && len < maxShorts) {
                Object pkt = Refl.call(tr, "nextFrame");
                if (pkt == null) break;
                ByteBuffer data = (ByteBuffer) Refl.call(pkt, "getData");
                Object ab = Refl.call(dec, "decodeFrame", data, ByteBuffer.allocate(1 << 17));
                ByteBuffer bb = ((ByteBuffer) Refl.call(ab, "getData")).duplicate();
                if (bb.remaining() == 0 && bb.position() > 0) bb.flip();
                boolean big = true;
                try {
                    Object fmt = Refl.call(ab, "getFormat");
                    out.rate = ((Number) Refl.call(fmt, "getSampleRate")).intValue();
                    out.ch = ((Number) Refl.call(fmt, "getChannels")).intValue();
                    big = (Boolean) Refl.call(fmt, "isBigEndian");
                } catch (Throwable ignored) { }
                int n = bb.remaining() / 2;
                if (len + n > buf.length) buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
                for (int i = 0; i < n; i++) {
                    int a = bb.get() & 0xFF, b = bb.get() & 0xFF;
                    buf[len++] = (short) (big ? (a << 8) | b : (b << 8) | a);
                }
            }
            if (len == 0) throw new IllegalStateException("AAC'den ses çıkmadı");
            out.data = java.util.Arrays.copyOf(buf, len);
            return out;
        } finally {
            try { Refl.call(chan, "close"); } catch (Throwable ignored) { }
        }
    }
}
