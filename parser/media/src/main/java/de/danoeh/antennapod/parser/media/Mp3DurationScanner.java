package de.danoeh.antennapod.parser.media;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Frame-accurate MP3 duration, for files whose duration is not stored in a
 * Xing/Info header and is therefore estimated from the bitrate by players.
 */
public final class Mp3DurationScanner {
    private static final int[] BITRATES_MPEG1_LAYER3 = {
            0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    private static final int[] BITRATES_MPEG2_LAYER3 = {
            0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
    private static final int[] SAMPLE_RATES_MPEG1 = {44100, 48000, 32000};
    private static final int[] SAMPLE_RATES_MPEG2 = {22050, 24000, 16000};
    private static final int[] SAMPLE_RATES_MPEG25 = {11025, 12000, 8000};
    private static final int SYNC_SEARCH_BYTES = 8192;

    private Mp3DurationScanner() {
    }

    public static final class Result {
        private final long durationMs;
        private final boolean hasXingHeader;

        private Result(long durationMs, boolean hasXingHeader) {
            this.durationMs = durationMs;
            this.hasXingHeader = hasXingHeader;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public boolean hasXingHeader() {
            return hasXingHeader;
        }
    }

    public static Result scan(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() < 4
                || file.length() > Integer.MAX_VALUE) {
            return null;
        }
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
                FileChannel channel = randomAccessFile.getChannel()) {
            MappedByteBuffer data = channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length());
            return scan(data);
        }
    }

    private static Result scan(ByteBuffer data) {
        int end = data.limit();
        if (end >= 128 && matches(data, end - 128, "TAG")) {
            end -= 128;
        }
        int position = skipId3v2(data);
        Header first = parseHeader(data, position, end);
        if (first == null) {
            return null;
        }
        boolean hasXingHeader = hasVbrHeader(data, position, first, end);
        long samples = 0;
        int frames = 0;
        while (position + 4 <= end) {
            Header header = parseHeader(data, position, end);
            if (header == null || !header.isCompatible(first)) {
                int next = findNextFrame(data, position + 1, end, first);
                if (next < 0) {
                    break;
                }
                position = next;
                continue;
            }
            samples += header.samplesPerFrame;
            frames++;
            position += header.frameLength;
        }
        if (frames == 0) {
            return null;
        }
        return new Result(Math.round(samples * 1000.0 / first.sampleRate), hasXingHeader);
    }

    private static int skipId3v2(ByteBuffer data) {
        if (data.limit() < 10 || !matches(data, 0, "ID3")) {
            return 0;
        }
        int flags = u8(data, 5);
        int size = ((u8(data, 6) & 0x7F) << 21)
                | ((u8(data, 7) & 0x7F) << 14)
                | ((u8(data, 8) & 0x7F) << 7)
                | (u8(data, 9) & 0x7F);
        int footer = (flags & 0x10) != 0 ? 10 : 0;
        return Math.min(data.limit(), 10 + size + footer);
    }

    private static boolean hasVbrHeader(ByteBuffer data, int position, Header header, int end) {
        int xing = position + header.xingOffset;
        if (xing + 4 <= end
                && (matches(data, xing, "Xing") || matches(data, xing, "Info"))) {
            return true;
        }
        return position + 36 + 4 <= end && matches(data, position + 36, "VBRI");
    }

    private static int findNextFrame(ByteBuffer data, int position, int end, Header first) {
        int limit = Math.min(end - 4, position + SYNC_SEARCH_BYTES);
        for (int i = position; i <= limit; i++) {
            Header header = parseHeader(data, i, end);
            if (header != null && header.isCompatible(first)) {
                return i;
            }
        }
        return -1;
    }

    private static Header parseHeader(ByteBuffer data, int position, int end) {
        if (position < 0 || position + 4 > end) {
            return null;
        }
        int first = u8(data, position);
        int second = u8(data, position + 1);
        int third = u8(data, position + 2);
        final int fourth = u8(data, position + 3);
        if (first != 0xFF || (second & 0xE0) != 0xE0) {
            return null;
        }
        int versionBits = (second >> 3) & 0x03;
        if (versionBits == 1 || ((second >> 1) & 0x03) != 1) {
            return null;
        }
        int bitrateIndex = (third >> 4) & 0x0F;
        int sampleRateIndex = (third >> 2) & 0x03;
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) {
            return null;
        }
        int version = versionBits == 3 ? 1 : versionBits == 2 ? 2 : 25;
        int[] sampleRates = version == 1 ? SAMPLE_RATES_MPEG1
                : version == 2 ? SAMPLE_RATES_MPEG2 : SAMPLE_RATES_MPEG25;
        int[] bitrates = version == 1 ? BITRATES_MPEG1_LAYER3 : BITRATES_MPEG2_LAYER3;
        int sampleRate = sampleRates[sampleRateIndex];
        int bitrate = bitrates[bitrateIndex] * 1000;
        int padding = (third >> 1) & 0x01;
        int frameLength = (version == 1 ? 144 : 72) * bitrate / sampleRate + padding;
        if (frameLength < 4 || position + frameLength > end) {
            return null;
        }
        boolean mono = ((fourth >> 6) & 0x03) == 3;
        int sideInfo = version == 1 ? (mono ? 17 : 32) : (mono ? 9 : 17);
        int crcBytes = (second & 0x01) == 0 ? 2 : 0;
        return new Header(version, sampleRate, version == 1 ? 1152 : 576,
                frameLength, 4 + crcBytes + sideInfo);
    }

    private static boolean matches(ByteBuffer data, int position, String text) {
        if (position < 0 || position + text.length() > data.limit()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (u8(data, position + i) != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int u8(ByteBuffer data, int position) {
        return data.get(position) & 0xFF;
    }

    private static final class Header {
        private final int version;
        private final int sampleRate;
        private final int samplesPerFrame;
        private final int frameLength;
        private final int xingOffset;

        private Header(int version, int sampleRate, int samplesPerFrame,
                       int frameLength, int xingOffset) {
            this.version = version;
            this.sampleRate = sampleRate;
            this.samplesPerFrame = samplesPerFrame;
            this.frameLength = frameLength;
            this.xingOffset = xingOffset;
        }

        private boolean isCompatible(Header other) {
            return version == other.version && sampleRate == other.sampleRate;
        }
    }
}
