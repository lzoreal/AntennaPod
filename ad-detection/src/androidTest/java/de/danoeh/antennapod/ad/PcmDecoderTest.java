package de.danoeh.antennapod.ad;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PcmDecoderTest {
    @Test
    public void decodesBundledMp3() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File out = new File(context.getCacheDir(), "decoder.mp3");
        try (InputStream in = context.getAssets().open("decoder.mp3");
             FileOutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
        PcmDecoder.Result r = PcmDecoder.decodeToMono(out);
        assertTrue("samples=" + r.pcm.length, r.pcm.length > 8000);
        assertTrue(r.sampleRate >= 8000);
        float[] pcm8k = Resampler.to8k(r.pcm, r.sampleRate);
        double seconds = pcm8k.length / 8000.0;
        assertTrue("seconds=" + seconds, seconds > 2.5 && seconds < 3.5);
    }
}
