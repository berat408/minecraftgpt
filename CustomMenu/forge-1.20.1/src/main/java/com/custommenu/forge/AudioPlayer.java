package com.custommenu.forge;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import net.sourceforge.jaad.aac.AACException;
import org.jcodec.containers.mp4.MP4Packet;
import org.jcodec.containers.mp4.boxes.AudioSampleEntry;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.jcodec.containers.mp4.demuxer.AbstractMP4DemuxerTrack;
import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.io.NIOUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class AudioPlayer {
    private static final Logger LOG = LoggerFactory.getLogger(AudioPlayer.class);
    private static final int MAX_SHORTS = 44100 * 2 * 180; // up to 3 mins stereo

    public static final class Decoded {
        public final String key;
        public final short[] pcm;
        public final int rate;
        public final int ch;

        public Decoded(String k, short[] p, int r, int c) {
            this.key = k;
            this.pcm = p;
            this.rate = r;
            this.ch = c;
        }
    }

    public volatile String status = "";
    private volatile String failedKey = "";
    private final AtomicReference<Decoded> ready = new AtomicReference<>();
    private final AtomicInteger generation = new AtomicInteger();
    private String key = "";
    private int source = 0;
    private int buffer = 0;

    public boolean isActive(String path) {
        return path != null && !path.isEmpty() && path.equals(key) && source != 0;
    }

    public void setVolume(float v) {
        if (source != 0) {
            try {
                AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0f, Math.min(1f, v)));
            } catch (Throwable ignored) {}
        }
    }

    public void update(String path, float volume, boolean loop) {
        if (path == null || path.isEmpty()) {
            stop();
            return;
        }
        if (path.equals(failedKey)) {
            return;
        }

        Decoded d = ready.getAndSet(null);
        if (d != null && path.equals(d.key)) {
            startOpenAL(d, volume, loop);
        }

        if (path.equals(key)) {
            setVolume(volume);
            return;
        }

        stop();
        key = path;
        final int gen = generation.incrementAndGet();
        Thread t = new Thread(() -> decodeAsync(path, gen), "CustomMenu-Audio");
        t.setDaemon(true);
        t.start();
    }

    private void decodeAsync(String path, int gen) {
        try {
            File file = new File(path);
            if (!file.isFile() || file.length() == 0) {
                failedKey = path;
                status = "Ses dosyası bulunamadı";
                return;
            }
            String lower = file.getName().toLowerCase(Locale.ROOT);
            Decoded decoded;
            if (lower.endsWith(".mp4")) {
                decoded = decodeMp4Audio(file, path);
            } else {
                decoded = decodeMp3(file, path);
            }
            if (generation.get() != gen) return;
            if (decoded == null || decoded.pcm.length == 0) {
                failedKey = path;
                status = "Ses verisi okunamadı";
                return;
            }
            ready.set(decoded);
            status = "";
        } catch (Throwable t) {
            if (generation.get() == gen) {
                failedKey = path;
                status = "Ses hatası: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                LOG.warn("Audio decode failed for {}: {}", path, t.toString());
            }
        }
    }

    private Decoded decodeMp3(File file, String path) throws Exception {
        short[] out = new short[44100 * 2 * 10];
        int count = 0;
        int rate = 0;
        int ch = 0;

        try (InputStream raw = new BufferedInputStream(new FileInputStream(file), 65536)) {
            // Skip ID3v2 tag manually if present to avoid Bitstream sync failure
            raw.mark(16);
            byte[] hdr = new byte[10];
            int n = raw.read(hdr);
            if (n == 10 && hdr[0] == 'I' && hdr[1] == 'D' && hdr[2] == '3') {
                int tagSize = ((hdr[6] & 0x7F) << 21)
                            | ((hdr[7] & 0x7F) << 14)
                            | ((hdr[8] & 0x7F) << 7)
                            | (hdr[9] & 0x7F);
                long toSkip = tagSize;
                while (toSkip > 0) {
                    long skipped = raw.skip(toSkip);
                    if (skipped <= 0) break;
                    toSkip -= skipped;
                }
            } else {
                raw.reset();
            }

            Bitstream bitstream = new Bitstream(raw);
            Decoder decoder = new Decoder();
            while (count < MAX_SHORTS) {
                Header h;
                try {
                    h = bitstream.readFrame();
                } catch (Exception frameEx) {
                    bitstream.closeFrame();
                    continue;
                }
                if (h == null) break;
                try {
                    SampleBuffer sb = (SampleBuffer) decoder.decodeFrame(h, bitstream);
                    if (rate == 0) {
                        ch = sb.getChannels();
                    }
                    short[] buf = sb.getBuffer();
                    int len = sb.getBufferLength();
                    if (count + len > out.length) {
                        out = Arrays.copyOf(out, Math.min(MAX_SHORTS, Math.max(out.length * 2, count + len)));
                    }
                    int copyLen = Math.min(len, out.length - count);
                    System.arraycopy(buf, 0, out, count, copyLen);
                    count += copyLen;
                } catch (Exception ignored) {
                } finally {
                    bitstream.closeFrame();
                }
            }
            bitstream.close();
        }
        return new Decoded(path, Arrays.copyOf(out, count), rate > 0 ? rate : 44100, ch > 0 ? ch : 2);
    }

    private Decoded decodeMp4Audio(File file, String path) throws Exception {
        FileChannelWrapper ch = null;
        try {
            ch = NIOUtils.readableChannel(file);
            MP4Demuxer demuxer = MP4Demuxer.createMP4Demuxer(ch);
            AbstractMP4DemuxerTrack audioTrack = demuxer.getAudioTracks().isEmpty() ? null : (AbstractMP4DemuxerTrack) demuxer.getAudioTracks().get(0);
            if (audioTrack == null) {
                return null;
            }
            ByteBuffer ascBuffer = audioTrack.getMeta().getCodecPrivate();
            byte[] asc = new byte[ascBuffer.remaining()];
            ascBuffer.get(asc);
            AudioSampleEntry audioFormat = (AudioSampleEntry) audioTrack.getSampleEntries()[0];
            net.sourceforge.jaad.aac.Decoder aac = new net.sourceforge.jaad.aac.Decoder(asc);
            net.sourceforge.jaad.aac.SampleBuffer sb = new net.sourceforge.jaad.aac.SampleBuffer();

            short[] out = new short[44100 * 2 * 10];
            int count = 0;
            int rate = (int) audioFormat.getSampleRate();
            int channels = audioFormat.getChannelCount();

            MP4Packet pkt;
            while (count < MAX_SHORTS && (pkt = (MP4Packet) audioTrack.nextFrame()) != null) {
                ByteBuffer bb = pkt.getData();
                byte[] frameBytes = new byte[bb.remaining()];
                bb.get(frameBytes);
                try {
                    aac.decodeFrame(frameBytes, sb);
                    byte[] pcmBytes = sb.getData();
                    int shortsLen = pcmBytes.length / 2;
                    if (count + shortsLen > out.length) {
                        out = Arrays.copyOf(out, Math.min(MAX_SHORTS, Math.max(out.length * 2, count + shortsLen)));
                    }
                    int copyLen = Math.min(shortsLen, out.length - count);
                    for (int i = 0; i < copyLen; i++) {
                        int hi = pcmBytes[i * 2] & 0xFF;
                        int lo = pcmBytes[i * 2 + 1] & 0xFF;
                        out[count + i] = (short) (sb.isBigEndian() ? ((hi << 8) | lo) : ((lo << 8) | hi));
                    }
                    count += copyLen;
                } catch (AACException ignored) {
                }
            }
            return new Decoded(path, Arrays.copyOf(out, count), rate, channels);
        } finally {
            if (ch != null) {
                try { ch.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private void startOpenAL(Decoded d, float volume, boolean loop) {
        stopOpenAL();
        try {
            buffer = AL10.alGenBuffers();
            source = AL10.alGenSources();
            ShortBuffer sb = BufferUtils.createShortBuffer(d.pcm.length);
            sb.put(d.pcm).flip();
            int format = (d.ch == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            AL10.alBufferData(buffer, format, sb, d.rate);
            AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
            AL10.alSourcei(source, AL10.AL_LOOPING, loop ? AL10.AL_TRUE : AL10.AL_FALSE);
            AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0f, Math.min(1f, volume)));
            AL10.alSourcePlay(source);
        } catch (Throwable t) {
            stopOpenAL();
            failedKey = d.key;
            status = "OpenAL hatası: " + t.getMessage();
        }
    }

    private void stopOpenAL() {
        if (source != 0) {
            try {
                AL10.alSourceStop(source);
                AL10.alDeleteSources(source);
            } catch (Throwable ignored) {}
            source = 0;
        }
        if (buffer != 0) {
            try {
                AL10.alDeleteBuffers(buffer);
            } catch (Throwable ignored) {}
            buffer = 0;
        }
    }

    public void stop() {
        generation.incrementAndGet();
        ready.set(null);
        key = "";
        failedKey = "";
        stopOpenAL();
    }
}
