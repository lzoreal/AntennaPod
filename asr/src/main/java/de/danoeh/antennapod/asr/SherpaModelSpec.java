package de.danoeh.antennapod.asr;

import java.io.File;

/** File locations and runtime knobs for one sherpa-onnx streaming transducer model. */
public final class SherpaModelSpec {
    private final String encoder;
    private final String decoder;
    private final String joiner;
    private final String tokens;
    private final String modelType;
    private final int numThreads;

    public SherpaModelSpec(String encoder, String decoder, String joiner, String tokens,
                           String modelType, int numThreads) {
        this.encoder = requireFile(encoder);
        this.decoder = requireFile(decoder);
        this.joiner = requireFile(joiner);
        this.tokens = requireFile(tokens);
        this.modelType = modelType == null ? "" : modelType;
        if (numThreads <= 0) {
            throw new IllegalArgumentException("numThreads must be positive: " + numThreads);
        }
        this.numThreads = numThreads;
    }

    public String getEncoder() {
        return encoder;
    }

    public String getDecoder() {
        return decoder;
    }

    public String getJoiner() {
        return joiner;
    }

    public String getTokens() {
        return tokens;
    }

    public String getModelType() {
        return modelType;
    }

    public int getNumThreads() {
        return numThreads;
    }

    private static String requireFile(String path) {
        if (path == null || !new File(path).isFile()) {
            throw new IllegalArgumentException("model file is missing: " + path);
        }
        return path;
    }
}
