package de.danoeh.antennapod.asr;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AsrModelStoreTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void installedModelResolvesToItsFiles() throws IOException {
        File root = folder.newFolder("models");
        AsrModelStore store = new AsrModelStore(root);
        File modelDir = store.modelDir(AsrModelLanguage.ZH);
        assertTrue(modelDir.mkdirs());
        AsrModelDescriptor descriptor = store.descriptor(AsrModelLanguage.ZH);
        for (String name : descriptor.getExpectedFiles()) {
            writeByte(new File(modelDir, name));
        }

        assertTrue(store.isInstalled(AsrModelLanguage.ZH));
        SherpaModelSpec spec = store.spec(AsrModelLanguage.ZH);
        assertEquals(2, spec.getNumThreads());
        assertEquals(new File(modelDir, descriptor.getEncoderFile()).getAbsolutePath(),
                spec.getEncoder());
        assertEquals(new File(modelDir, descriptor.getTokensFile()).getAbsolutePath(),
                spec.getTokens());
    }

    @Test
    public void missingFilesMeanNotInstalled() throws IOException {
        File root = folder.newFolder("models");
        AsrModelStore store = new AsrModelStore(root);
        File modelDir = store.modelDir(AsrModelLanguage.ZH);
        assertTrue(modelDir.mkdirs());
        writeByte(new File(modelDir, store.descriptor(AsrModelLanguage.ZH).getEncoderFile()));

        assertFalse(store.isInstalled(AsrModelLanguage.ZH));
    }

    @Test(expected = IllegalStateException.class)
    public void specForMissingLanguageFails() throws IOException {
        AsrModelStore store = new AsrModelStore(folder.newFolder("models"));
        store.spec(AsrModelLanguage.EN);
    }

    @Test
    public void feedLanguageSelectsTheModel() {
        AsrModelStore store = new AsrModelStore(folder.getRoot());

        assertEquals(AsrModelLanguage.ZH,
                store.languageForFeed("zh-CN", AsrModelLanguage.EN));
        assertEquals(AsrModelLanguage.EN,
                store.languageForFeed("en-GB", AsrModelLanguage.ZH));
        assertEquals(AsrModelLanguage.ZH,
                store.languageForFeed(null, AsrModelLanguage.ZH));
    }

    private static void writeByte(File file) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(1);
        }
    }
}
