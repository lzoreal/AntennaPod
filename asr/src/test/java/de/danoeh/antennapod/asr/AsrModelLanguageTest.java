package de.danoeh.antennapod.asr;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AsrModelLanguageTest {
    @Test
    public void chineseTagsMapToChinese() {
        assertEquals(AsrModelLanguage.ZH,
                AsrModelLanguage.fromFeedLanguage("zh-CN", AsrModelLanguage.EN));
        assertEquals(AsrModelLanguage.ZH,
                AsrModelLanguage.fromFeedLanguage("cmn-Hans", AsrModelLanguage.EN));
        assertEquals(AsrModelLanguage.ZH,
                AsrModelLanguage.fromFeedLanguage(" ZH ", AsrModelLanguage.EN));
    }

    @Test
    public void englishTagsMapToEnglish() {
        assertEquals(AsrModelLanguage.EN,
                AsrModelLanguage.fromFeedLanguage("en-US", AsrModelLanguage.ZH));
        assertEquals(AsrModelLanguage.EN,
                AsrModelLanguage.fromFeedLanguage("en", AsrModelLanguage.ZH));
    }

    @Test
    public void unknownOrMissingLanguageUsesTheFallback() {
        assertEquals(AsrModelLanguage.EN,
                AsrModelLanguage.fromFeedLanguage("fr-FR", AsrModelLanguage.EN));
        assertEquals(AsrModelLanguage.ZH,
                AsrModelLanguage.fromFeedLanguage(null, AsrModelLanguage.ZH));
        assertEquals(AsrModelLanguage.EN,
                AsrModelLanguage.fromFeedLanguage("", AsrModelLanguage.EN));
    }
}
