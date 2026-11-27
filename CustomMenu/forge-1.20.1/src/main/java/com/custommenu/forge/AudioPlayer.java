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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Ses dosyasını PCM'e çevirip Minecraft'ın OpenAL'ında çalar. Biçim, uzantıya değil dosyanın içeriğine bakılarak seçilir:
 *  MP3 (hatalı karelere toleranslı), OGG, WAV, M4A/MP4 (AAC). Ana (render) thread'den çağrılmalı. */
public final class AudioPlayer {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int MAX_SHORTS = 24_000_000; // ~3-4 dk stereo; fazlası kesilir
    public static volatile String status = "";
    private static volatile String failedKey = "";

    private static final class Decoded {
        final String key; final short[] pcm; final int rate, ch;
        Decoded(String k, short[] p, int r, int c) { key = k; pcm = p; rate = r; ch = c; }
    }
    private static final AtomicReference<Decoded> ready = new AtomicReference<>();
    private static volatile int generation = 0;
    private static String key = "";
    private static int source = 0, buffer = 0;

    private AudioPlayer() { }

    public static boolean isActive(String path) { return !key.isEmpty() && key.equals(path); }
    public static boolean hasFailed() { return !failedKey.isEmpty(); }
    public static boolean isPlaying() {
        try { return source != 0 && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING; } catch (Throwable t) { return false; }
    }
    public static void setVolume(float v) {
        try { if (source != 0) AL10.alSourcef(source, AL10.AL_GAIN, v); } catch (Throwable ignored) { }
    }

    /** path boşsa durdurur. Aynı yol tekrar gelirse ses seviyesini/döngüyü günceller. Durum metni korunur. */
    public static void update(String path, float volume, boolean loop) {
        try {
            if (path.isEmpty()) { if (!key.isEmpty()) stop(); failedKey = ""; return; }
            if (path.equals(failedKey)) return;
            failedKey = "";
            if (!path.equals(key)) { stop(); key = path; startDecode(path); }
            if (source != 0) {
                AL10.alSourcef(source, AL10.AL_GAIN, volume);
                AL10.alSourcei(source, AL10.AL_LOOPING, loop ? 1 : 0);
                int st = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                status = st == AL10.AL_PLAYING ? "Ses çalıyor" : st == AL10.AL_STOPPED ? "Ses bitti" : "Ses durumu: 0x" + Integer.toHexString(st);
            } else {
                Decoded d = ready.get();
                if (d != null && d.key.equals(key)) play(d, volume, loop);
            }
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

    private static void play(Decoded d, float volume, boolean loop) {
        if (ALC10.alcGetCurrentContext() == 0L) throw new IllegalStateException("OpenAL ses aygıtı yok");
        AL10.alGetError();
        ShortBuffer sb = BufferUtils.createShortBuffer(d.pcm.length);
        sb.put(d.pcm).flip();
        buffer = AL10.alGenBuffers(); check("buffer");
        AL10.alBufferData(buffer, d.ch == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16, sb, d.rate); check("veri");
        source = AL10.alGenSources(); check("kaynak");
        AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, 1);
        AL10.alSourcef(source, AL10.AL_GAIN, volume);
        AL10.alSourcei(source, AL10.AL_LOOPING, loop ? 1 : 0);
        AL10.alSourcePlay(source); check("çal");
        status = "Ses çalıyor";
    }

    public static void stop() {
        generation++;
        ready.set(null);
        key = "";
        try {
            if (source != 0) { AL10.alSourceStop(source); AL10.alDeleteSources(source); }
            if (buffer != 0) AL10.alDeleteBuffers(buffer);
        } catch (Throwable ignored) { }
        source = 0; buffer = 0;
    }

    private static void startDecode(String path) {
        final int gen = generation; final String k = path;
        status = "Ses yükleniyor...";
        Thread t = new Thread(() -> {
            try {
                File f = new File(path);
                if (!f.isFile()) throw new IllegalStateException("Dosya bulunamadı");
                BooleanSupplier cancelled = () -> gen != generation;
                Decoded d = decodeAny(f, k, cancelled);
                if (gen != generation) return;
                if (d == null || d.pcm.length == 0) {
                    status = "Ses hatası: dosyadan ses çıkmadı (" + head(f) + ")"; failedKey = k; return;
                }
                ready.set(d);
            } catch (Throwable ex) { /* bozuk dosya oyunu çökertmez */
                LOG.warn("CustomMenu: ses çözülemedi", ex);
                status = "Ses hatası: " + ex; failedKey = k;
            }
        }, "CustomMenu-Audio");
        t.setDaemon(true); t.start();
    }

    private static byte[] headBytes(File f) throws Exception {
        byte[] h = new byte[16]; int n;
        try (FileInputStream in = new FileInputStream(f)) { n = in.read(h); }
        return n < 0 ? new byte[0] : java.util.Arrays.copyOf(h, n);
    }
    private static String head(File f) {
        try {
            byte[] h = headBytes(f); StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(8, h.length); i++) sb.append(String.format("%02x ", h[i]));
            return "başlık: " + sb.toString().trim();
        } catch (Throwable t) { return "başlık okunamadı"; }
    }

    /** Dosyanın içeriğine bakıp doğru çözücüyü seçer (uzantı yanıltıcı olabilir). */
    private static Decoded decodeAny(File f, String k, BooleanSupplier cancelled) throws Exception {
        byte[] h = headBytes(f);
        boolean ogg = h.length >= 4 && h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S';
        boolean wav = h.length >= 4 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F';
        boolean mp4 = h.length >= 8 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p';
        if (ogg) return decodeOgg(f, k);
        if (wav) return decodeWav(f, k);
        if (mp4) {
            AacExtractor.Pcm p = AacExtractor.decode(f, MAX_SHORTS, cancelled);
            return new Decoded(k, p.data, p.rate, p.ch);
        }
        return decodeMp3(f, k, cancelled);
    }

    /** Hatalı/bozuk karelere takılmadan atlayıp devam eder. */
    private static Decoded decodeMp3(File f, String k, BooleanSupplier cancelled) throws Exception {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 16)) {
            Bitstream bs = new Bitstream(in);
            Decoder dec = new Decoder();
            short[] out = new short[1 << 20]; int len = 0, errors = 0;
            while (!cancelled.getAsBoolean() && len < MAX_SHORTS && errors < 3000) {
                Header h;
                try { h = bs.readFrame(); } catch (Throwable e) { errors++; continue; }
                if (h == null) break;
                try {
                    SampleBuffer sb = (SampleBuffer) dec.decodeFrame(h, bs);
                    int n = sb.getBufferLength();
                    if (len + n > out.length) out = java.util.Arrays.copyOf(out, Math.max(out.length * 2, len + n));
                    System.arraycopy(sb.getBuffer(), 0, out, len, n); len += n;
                } catch (Throwable e) { errors++; }
                try { bs.closeFrame(); } catch (Throwable ignored) { }
            }
            if (len == 0) return null;
            int ch = dec.getOutputChannels();
            return new Decoded(k, java.util.Arrays.copyOf(out, len), dec.getOutputFrequency(), ch);
        }
    }

    private static Decoded decodeOgg(File f, String k) throws Exception {
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
            if (chn.get(0) > 2) throw new IllegalStateException("2'den fazla kanal desteklenmiyor");
            return new Decoded(k, arr, sr.get(0), chn.get(0));
        } finally { MemoryUtil.memFree(mem); }
    }

    private static Decoded decodeWav(File f, String k) throws Exception {
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
                return new Decoded(k, s, (int) sf.getSampleRate(), sf.getChannels());
            }
        }
    }
}
