package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pinned model packs. Upstream does not publish per-file SHA-256 values. */
public final class AsrModelCatalog {
    private static final AsrModelDescriptor X_ASR = new AsrModelDescriptor(
            "sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
                    + "sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8"
                    + "-2026-06-05.tar.bz2",
            133895136L,
            "encoder.int8.onnx",
            "decoder.onnx",
            "joiner.int8.onnx",
            "tokens.txt",
            modelHashes());

    private static Map<String, String> modelHashes() {
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("encoder.int8.onnx",
                "908596dcc137a73b95be908ca55e88caa1b3dbbe8027c171615f4b0609c5eb1e");
        hashes.put("decoder.onnx",
                "a1cbc9eac2d5e3fb6617a218c67ad6daaa7f4e0fd225f08b2c22ab0413c8c257");
        hashes.put("joiner.int8.onnx",
                "aedb7fa697b2ab43f20499826fff7c997eea7d67db77be97769aeeeb726e63b3");
        hashes.put("tokens.txt",
                "b818a60878b9aae978cbb8ad594acbd403d76d1af2e31ef4197c84e2dbdba27c");
        return Collections.unmodifiableMap(hashes);
    }

    private AsrModelCatalog() {
    }

    @NonNull
    public static AsrModelDescriptor model() {
        return X_ASR;
    }
}
