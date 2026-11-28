package com.custommenu.core;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.BooleanSupplier;

/** MP4/M4A dosyasındaki AAC ses kanalını PCM'e çevirir. AAC ayar baytları (AudioSpecificConfig) dosyadan okunur,
 *  çözme işi JCodec'in AACDecoder'ına yansıma ile yaptırılır (sürüm farkları derlemeyi bozmasın diye). */
public final class AacExtractor {
    public static final class Pcm { public short[] data; public int rate = 44100, ch = 2; }
    private AacExtractor() { }

    /** moov kutusunu bulur, içindeki esds -> DecoderSpecificInfo (AudioSpecificConfig) baytlarını döndürür. */
    public static byte[] findAsc(File f) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length(), pos = 0;
            while (pos + 8 <= len) {
                raf.seek(pos);
                long size = raf.readInt() & 0xFFFFFFFFL;
                byte[] t = new byte[4]; raf.readFully(t);
                String type = new String(t, "ISO-8859-1");
                long hdr = 8;
                if (size == 1) { size = raf.readLong(); hdr = 16; }
                else if (size == 0) size = len - pos;
                if (size < hdr) break;
                if (type.equals("moov")) {
                    long n = size - hdr;
                    if (n > 64L * 1024 * 1024) throw new IllegalStateException("moov çok büyük");
                    byte[] b = new byte[(int) n]; raf.readFully(b);
                    byte[] asc = scan(b);
                    if (asc != null) return asc;
                }
                pos += size;
            }
        }
        throw new IllegalStateException("AAC ayar kutusu (esds) bulunamadı");
    }

    private static byte[] scan(byte[] b) {
        for (int i = 0; i + 8 < b.length; i++) {
            if (b[i] != 'e' || b[i + 1] != 's' || b[i + 2] != 'd' || b[i + 3] != 's') continue;
            try {
                int p = i + 4 + 4;                       // 'esds' + sürüm/bayraklar
                if ((b[p] & 0xFF) != 0x03) continue;     // ES_Descriptor
                int[] l = len(b, p + 1); p = l[1];
                p += 2;                                  // ES_ID
                int flags = b[p++] & 0xFF;
                if ((flags & 0x80) != 0) p += 2;
                if ((flags & 0x40) != 0) { int ul = b[p++] & 0xFF; p += ul; }
                if ((flags & 0x20) != 0) p += 2;
                if ((b[p] & 0xFF) != 0x04) continue;     // DecoderConfigDescriptor
                l = len(b, p + 1); p = l[1];
                p += 13;                                 // nesne tipi, akış tipi, tampon, bit hızları
                if ((b[p] & 0xFF) != 0x05) continue;     // DecoderSpecificInfo
                l = len(b, p + 1); p = l[1];
                if (l[0] <= 0 || p + l[0] > b.length) continue;
                return java.util.Arrays.copyOfRange(b, p, p + l[0]);
            } catch (RuntimeException ignored) { /* sıradaki esds */ }
        }
        return null;
    }
    private static int[] len(byte[] b, int i) { // MPEG-4 değişken uzunluk: {değer, sonraki indeks}
        int v = 0, k = 0, c;
        do { c = b[i++] & 0xFF; v = (v << 7) | (c & 0x7F); k++; } while ((c & 0x80) != 0 && k < 4);
        return new int[]{v, i};
    }

    public static Pcm decode(File f, int maxShorts, BooleanSupplier cancelled) throws Exception {
        byte[] asc = findAsc(f);
        Object chan = Refl.callStatic("org.jcodec.common.io.NIOUtils", "readableChannel", f);
        try {
            Object dem = Refl.callStatic("org.jcodec.containers.mp4.demuxer.MP4Demuxer", "createMP4Demuxer", chan);
            List<?> tracks = (List<?>) Refl.call(dem, "getAudioTracks");
            if (tracks == null || tracks.isEmpty()) throw new IllegalStateException("Dosyada ses kanalı yok");
            Object tr = tracks.get(0);
            Object dec = Refl.newInstance("org.jcodec.codecs.aac.AACDecoder", ByteBuffer.wrap(asc));

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
                    int a = bb.get() & 0xFF, c = bb.get() & 0xFF;
                    buf[len++] = (short) (big ? (a << 8) | c : (c << 8) | a);
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
