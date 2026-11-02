package de.danoeh.antennapod.parser.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.Test;

public class Mp3DurationScannerTest {
    private static final int FRAME_LENGTH = 417;
    private static final int FRAME_COUNT = 1000;

    @Test
    public void scansFramesWithoutXingHeader() throws IOException {
        File file = writeMp3(false, false);

        Mp3DurationScanner.Result result = Mp3DurationScanner.scan(file);

        assertNotNull(result);
        assertFalse(result.hasXingHeader());
        assertEquals(Math.round(FRAME_COUNT * 1152 * 1000.0 / 44100), result.getDurationMs());
    }

    @Test
    public void detectsXingHeader() throws IOException {
        File file = writeMp3(true, false);

        Mp3DurationScanner.Result result = Mp3DurationScanner.scan(file);

        assertNotNull(result);
        assertTrue(result.hasXingHeader());
    }

    @Test
    public void ignoresId3Tags() throws IOException {
        File file = writeMp3(false, true);

        Mp3DurationScanner.Result result = Mp3DurationScanner.scan(file);

        assertNotNull(result);
        assertEquals(Math.round(FRAME_COUNT * 1152 * 1000.0 / 44100), result.getDurationMs());
    }

    @Test
    public void rejectsNonMp3() throws IOException {
        File file = File.createTempFile("not-mp3", ".bin");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[4096]);
        }
        try {
            assertNull(Mp3DurationScanner.scan(file));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private File writeMp3(boolean xing, boolean id3v1) throws IOException {
        File file = File.createTempFile("mp3-duration", ".mp3");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0});
            byte[] frame = new byte[FRAME_LENGTH];
            frame[0] = (byte) 0xFF;
            frame[1] = (byte) 0xFB;
            frame[2] = (byte) 0x90;
            frame[3] = 0;
            if (xing) {
                writeAscii(frame, 4 + 32, "Xing");
            }
            for (int i = 0; i < FRAME_COUNT; i++) {
                output.write(frame);
            }
            if (id3v1) {
                byte[] tag = new byte[128];
                writeAscii(tag, 0, "TAG");
                output.write(tag);
            }
        }
        file.deleteOnExit();
        return file;
    }

    private static void writeAscii(byte[] target, int offset, String text) {
        for (int i = 0; i < text.length(); i++) {
            target[offset + i] = (byte) text.charAt(i);
        }
    }
}
