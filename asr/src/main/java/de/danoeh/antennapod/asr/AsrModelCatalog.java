package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;

import java.util.Collections;

/** Pinned model packs. Upstream does not publish per-file SHA-256 values. */
public final class AsrModelCatalog {
    private static final AsrModelDescriptor ZH = new AsrModelDescriptor(
            AsrModelLanguage.ZH,
            "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
                    + "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2",
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
            Collections.emptyMap());

    private static final AsrModelDescriptor EN = new AsrModelDescriptor(
            AsrModelLanguage.EN,
            "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
                    + "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2",
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
            Collections.emptyMap());

    private AsrModelCatalog() {
    }

    @NonNull
    public static AsrModelDescriptor forLanguage(@NonNull AsrModelLanguage language) {
        return language == AsrModelLanguage.ZH ? ZH : EN;
    }
}
