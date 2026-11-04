package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;

import java.util.Collections;

/** Pinned model packs. Upstream does not publish per-file SHA-256 values. */
public final class AsrModelCatalog {
    private static final AsrModelDescriptor X_ASR = new AsrModelDescriptor(
            AsrModelLanguage.EN,
            "sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
                    + "sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8"
                    + "-2026-06-05.tar.bz2",
            133895136L,
            "encoder.int8.onnx",
            "decoder.onnx",
            "joiner.int8.onnx",
            "tokens.txt",
            Collections.emptyMap());

    private AsrModelCatalog() {
    }

    @NonNull
    public static AsrModelDescriptor forLanguage(@NonNull AsrModelLanguage language) {
        return X_ASR;
    }
}
