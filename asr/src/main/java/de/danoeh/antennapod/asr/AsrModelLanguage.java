package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;

import java.util.Locale;

/** Languages that have their own on-device model. */
public enum AsrModelLanguage {
    ZH,
    EN;

    @NonNull
    public static AsrModelLanguage fromFeedLanguage(String feedLanguage,
                                                    @NonNull AsrModelLanguage fallback) {
        if (feedLanguage != null) {
            String language = feedLanguage.trim().toLowerCase(Locale.ROOT);
            if (language.startsWith("zh") || language.startsWith("cmn")) {
                return ZH;
            }
            if (language.startsWith("en")) {
                return EN;
            }
        }
        return fallback;
    }
}
