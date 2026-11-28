package com.custommenu.forge;

import com.custommenu.core.AacExtractor;
import com.mojang.logging.LogUtils;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Ses akış olarak çalınır: arka plan thread'i dosyayı ~0,5 sn'lik parçalar halinde çözer, ana thread parçaları
 * OpenAL tampon kuyruğuna verir. Böylece dev PCM dizileri/doğrudan tamponlar oluşmaz (bellek, çöp toplama, takılma azalır)
 * ve ses neredeyse anında başlar. Biçim, uzantıya değil dosya içeriğine bakılarak seçilir:
 * MP3 (akış, hatalı karelere toleranslı), OGG, WAV, M4A/MP4 (AAC). Ana (render) thread'den çağrılmalı.
 */
public final class AudioPlayer {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int MAX_SHORTS = 24_000_000; // dizi kaynaklarında üst sınır (~3-4 dk stereo)
    private static final int NBUF = 4;
    public static volatile String status = "";
    private static volatile String failedKey = "";

    private interface PcmSource {
        int rate(); int channels();
        int read(short[] dst) throws Exception;   // 0: bitti
        void reset() throws Exception;            // baştan
        void close();
    }

    private static final class Stream {
        final String key; final ArrayBlockingQueue<short[]> q = new ArrayBlockingQueue<>(6);
        volatile boolean stop, ended, failed, loop; volatile int rate, ch; volatile String error = "";
        Stream(String k, boolean loop) { key = k; this.loop = loop; }
    }

    private static Stream stream;
    private static String key = "";
    private static int source = 0, nBufs = 0, format = 0, rate = 0;
    private static final int[] bufs = new int[NBUF];
    private static final ArrayDeque<Integer> free = new ArrayDeque<>();
    private static ShortBuffer upload;
    private static float lastVol = 1f; private static boolean lastLoop = true;

    private AudioPlayer() { }

    public static boolean isActive(String path) { return !key.isEmpty() && key.equals(path); }
    public static boolean hasFailed() { return !failedKey.isEmpty(); }
    public static boolean isPlaying() {
        try { return source != 0 && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING; } catch (Throwable t) { return false; }
    }
    public static void setVolume(float v) {
        lastVol = v;
        try { if (source != 0) AL10.alSourcef(source, AL10.AL_GAIN, v); } catch (Throwable ignored) { }
    }
    /** Eski API: artık önbellek yok (akış anında başlıyor). */
    public static void clearCache() { }

    /** Aynı sesi baştan çalar (video döngüsüyle senkron kalmak için). */
    public static void rewind() {
        String k = key;
        if (k.isEmpty()) return;
        boolean lp = lastLoop;
        stop(); key = k; stream = startStream(k, lp);
    }

    /** path boşsa durdurur. Aynı yol tekrar gelirse ses seviyesini/döngüyü günceller. Durum metni korunur. */
    public static void update(String path, float volume, boolean loop) {
        try {
            if (path.isEmpty()) { if (!key.isEmpty()) stop(); failedKey = ""; return; }
            if (path.equals(failedKey)) return;
            failedKey = "";
            lastVol = volume; lastLoop = loop;
            if (!path.equals(key)) { stop(); key = path; stream = startStream(path, loop); status = "Ses yükleniyor..."; }
            Stream s = stream;
            if (s == null) return;
            s.loop = loop;
            pump(s, volume);
        } catch (Throwable t) {
            LOG.warn("CustomMenu: ses hatası", t);
            status = "Ses hatası: " + t;
            String k = path; stop(); failedKey = k;
        }
    }

    private static void check(String where) {
        int e = AL10.alGetError();
        if (e != AL10.AL_NO_ERROR) throw new IllegalStateException("OpenAL hata kodu 0x" + Integer.toHexString(e) + " (" + where + ")");
    }

    /** Her tick: biten tamponları geri al, hazır parçalarla doldur, gerekirse çalmayı sürdür. */
    private static void pump(Stream s, float volume) {
        if (s.failed) throw new IllegalStateException(s.error);
        if (source == 0) {
            if (s.rate > 0 && (s.q.size() >= 2 || (s.ended && !s.q.isEmpty()))) startSource(s, volume);
            return;
        }
        AL10.alSourcef(source, AL10.AL_GAIN, volume);
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) free.add(AL10.alSourceUnqueueBuffers(source));
        while (!free.isEmpty()) {
            short[] chunk = s.q.poll();
            if (chunk == null) break;
            int b = free.poll();
            fill(b, chunk);
            AL10.alSourceQueueBuffers(source, b);
        }
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        int st = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        if (st != AL10.AL_PLAYING) {
            if (queued > 0) AL10.alSourcePlay(source);                       // veri yetmeyip durduysa sürdür
            else if (s.ended && s.q.isEmpty()) { status = "Ses bitti"; return; }
        }
        status = "Ses çalıyor";
    }

    private static void fill(int buf, short[] chunk) {
        upload.clear();
        upload.put(chunk, 0, chunk.length);
        upload.flip();
        AL10.alBufferData(buf, format, upload, rate);
        check("veri");
    }

    private static void startSource(Stream s, float volume) {
        if (ALC10.alcGetCurrentContext() == 0L) throw new IllegalStateException("OpenAL ses aygıtı yok");
        AL10.alGetError();
        rate = s.rate; format = s.ch == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        upload = BufferUtils.createShortBuffer(Math.max(4096, rate * Math.max(1, s.ch)));
        source = AL10.alGenSources(); check("kaynak");
        free.clear(); nBufs = 0;
        for (int i = 0; i < NBUF; i++) { bufs[i] = AL10.alGenBuffers(); nBufs++; free.add(bufs[i]); }
        check("buffer");
        while (!free.isEmpty()) {
            short[] c = s.q.poll();
            if (c == null) break;
            int b = free.poll();
            fill(b, c);
            AL10.alSourceQueueBuffers(source, b);
        }
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, 1);
        AL10.alSourcef(source, AL10.AL_GAIN, volume);
        AL10.alSourcePlay(source); check("çal");
        status = "Ses çalıyor";
    }

    public static void stop() {
        Stream s = stream;
        if (s != null) s.stop = true;
        stream = null; key = "";
        try {
            if (source != 0) { AL10.alSourceStop(source); AL10.alSourcei(source, AL10.AL_BUFFER, 0); AL10.alDeleteSources(source); }
            for (int i = 0; i < nBufs; i++) AL10.alDeleteBuffers(bufs[i]);
        } catch (Throwable ignored) { }
        source = 0; nBufs = 0; free.clear(); upload = null;
    }

    // ---------------------------------------------------------------- çözme (arka plan)

    private static Stream startStream(String path, boolean loop) {
        Stream s = new Stream(path, loop);
        Thread t = new Thread(() -> produce(s, new File(path)), "CustomMenu-Audio");
        t.setDaemon(true); t.start();
        return s;
    }

    private static void produce(Stream s, File f) {
        PcmSource src = null;
        try {
            if (!f.isFile()) throw new IllegalStateException("Dosya bulunamadı");
            src = open(f, s);
            if (src.channels() > 2) throw new IllegalStateException("2'den fazla kanal desteklenmiyor");
            s.ch = src.channels(); s.rate = src.rate();
            int chunk = Math.max(4096, s.rate * s.ch / 2);  // ~0,5 sn
            boolean any = false;
            while (!s.stop) {
                short[] buf = new short[chunk];
                int n = src.read(buf);
                if (n <= 0) {
                    if (!any) throw new IllegalStateException("dosyadan ses çıkmadı (" + head(f) + ")");
                    if (!s.loop) break;
                    src.reset(); any = false;
                    continue;
                }
                any = true;
                if (n < buf.length) buf = Arrays.copyOf(buf, n);
                while (!s.stop && !s.q.offer(buf, 50, TimeUnit.MILLISECONDS)) { /* tüketici bekleniyor */ }
            }
            s.ended = true;
        } catch (Throwable ex) {
            if (!s.stop) {
                LOG.warn("CustomMenu: ses çözülemedi", ex);
                s.error = String.valueOf(ex); s.failed = true; status = "Ses hatası: " + ex;
            }
        } finally {
            if (src != null) src.close();
        }
    }

    private static String head(File f) {
        try {
            byte[] h = headBytes(f); StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(8, h.length); i++) sb.append(String.format("%02x ", h[i]));
            return "başlık: " + sb.toString().trim();
        } catch (Throwable t) { return "başlık okunamadı"; }
    }
    private static byte[] headBytes(File f) throws Exception {
        byte[] h = new byte[16]; int n;
        try (FileInputStream in = new FileInputStream(f)) { n = in.read(h); }
        return n < 0 ? new byte[0] : Arrays.copyOf(h, n);
    }

    /** Dosyanın içeriğine bakıp doğru kaynağı seçer (uzantı yanıltıcı olabilir). */
    private static PcmSource open(File f, Stream s) throws Exception {
        byte[] h = headBytes(f);
        boolean ogg = h.length >= 4 && h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S';
        boolean wav = h.length >= 4 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F';
        boolean mp4 = h.length >= 8 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p';
        if (ogg) return decodeOgg(f);
        if (wav) return decodeWav(f);
        if (mp4) {
            AacExtractor.Pcm p = AacExtractor.decode(f, MAX_SHORTS, () -> s.stop);
            return new ArraySource(p.data, p.rate, p.ch);
        }
        return new Mp3Source(f);
    }

    private static final class ArraySource implements PcmSource {
        private final short[] d; private final int rate, ch; private int pos;
        ArraySource(short[] d, int rate, int ch) { this.d = d; this.rate = rate; this.ch = ch; }
        public int rate() { return rate; }
        public int channels() { return ch; }
        public int read(short[] dst) { int n = Math.min(dst.length, d.length - pos); if (n <= 0) return 0; System.arraycopy(d, pos, dst, 0, n); pos += n; return n; }
        public void reset() { pos = 0; }
        public void close() { }
    }

    /** MP3'ü kare kare akıtır; bozuk karelere takılmadan atlayıp devam eder. */
    private static final class Mp3Source implements PcmSource {
        private final File f; private BufferedInputStream in; private Bitstream bs; private Decoder dec;
        private short[] pend = new short[2304]; private int pendPos, pendLen, errors, rate, ch; private boolean eof;

        Mp3Source(File f) throws Exception {
            this.f = f;
            open();
            while (pendLen == 0 && !eof) nextFrame();
            if (pendLen == 0) throw new IllegalStateException("MP3'ten ses çıkmadı (" + head(f) + ")");
            rate = dec.getOutputFrequency(); ch = dec.getOutputChannels();
        }
        private void open() throws Exception {
            close();
            in = new BufferedInputStream(new FileInputStream(f), 1 << 16);
            bs = new Bitstream(in); dec = new Decoder();
            pendPos = 0; pendLen = 0; errors = 0; eof = false;
        }
        private void nextFrame() {
            pendPos = 0; pendLen = 0;
            while (!eof && pendLen == 0) {
                Header h;
                try { h = bs.readFrame(); } catch (Throwable e) { if (++errors > 3000) eof = true; continue; }
                if (h == null) { eof = true; break; }
                try {
                    SampleBuffer sb = (SampleBuffer) dec.decodeFrame(h, bs);
                    int n = sb.getBufferLength();
                    if (n > pend.length) pend = new short[n];
                    System.arraycopy(sb.getBuffer(), 0, pend, 0, n);
                    pendLen = n;
                } catch (Throwable e) { if (++errors > 3000) eof = true; }
                try { bs.closeFrame(); } catch (Throwable ignored) { }
            }
        }
        public int rate() { return rate; }
        public int channels() { return ch; }
        public int read(short[] dst) {
            int filled = 0;
            while (filled < dst.length) {
                if (pendPos >= pendLen) { nextFrame(); if (pendLen == 0) break; }
                int n = Math.min(dst.length - filled, pendLen - pendPos);
                System.arraycopy(pend, pendPos, dst, filled, n);
                pendPos += n; filled += n;
            }
            return filled;
        }
        public void reset() throws Exception { open(); }
        public void close() { try { if (in != null) in.close(); } catch (Throwable ignored) { } in = null; }
    }

    private static PcmSource decodeOgg(File f) throws Exception {
        byte[] bytes = Files.readAllBytes(f.toPath());
        ByteBuffer mem = MemoryUtil.memAlloc(bytes.length);
        try (MemoryStack st = MemoryStack.stackPush()) {
            mem.put(bytes).flip();
            IntBuffer chn = st.mallocInt(1), sr = st.mallocInt(1);
            ShortBuffer pcm = STBVorbis.stb_vorbis_decode_memory(mem, chn, sr);
            if (pcm == null) throw new IllegalStateException("OGG çözülemedi");
            short[] arr = new short[Math.min(pcm.remaining(), MAX_SHORTS)];
            pcm.get(arr);
            LibCStdlib.free(pcm);
            return new ArraySource(arr, sr.get(0), chn.get(0));
        } finally { MemoryUtil.memFree(mem); }
    }

    private static PcmSource decodeWav(File f) throws Exception {
        try (AudioInputStream src = AudioSystem.getAudioInputStream(f)) {
            AudioFormat sf = src.getFormat();
            if (sf.getChannels() > 2) throw new IllegalStateException("2'den fazla kanal desteklenmiyor");
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sf.getSampleRate(), 16,
                    sf.getChannels(), sf.getChannels() * 2, sf.getSampleRate(), false);
            try (AudioInputStream in = AudioSystem.getAudioInputStream(target, src)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16]; int n;
                while ((n = in.read(buf)) > 0 && bo.size() < MAX_SHORTS * 2) bo.write(buf, 0, n);
                byte[] b = bo.toByteArray(); short[] s = new short[b.length / 2];
                for (int i = 0; i < s.length; i++) s[i] = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
                return new ArraySource(s, (int) sf.getSampleRate(), sf.getChannels());
            }
        }
    }
}
