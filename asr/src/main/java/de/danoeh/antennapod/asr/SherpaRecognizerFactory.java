package de.danoeh.antennapod.asr;

import android.util.Log;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

/** Builds a sherpa-onnx streaming transducer recognizer from local model files. */
public final class SherpaRecognizerFactory {
    private static final String TAG = "AsrRecognizerFactory";

    private SherpaRecognizerFactory() {
    }

    public static SherpaStreamingRecognizer create(SherpaModelSpec spec) {
        Log.i(TAG, "Loading model " + spec.getEncoder());
        FeatureConfig featureConfig = new FeatureConfig();
        featureConfig.setSampleRate(PcmChunk.SAMPLE_RATE_HZ);
        featureConfig.setFeatureDim(80);
        featureConfig.setDither(0f);

        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(spec.getEncoder());
        transducer.setDecoder(spec.getDecoder());
        transducer.setJoiner(spec.getJoiner());

        OnlineModelConfig modelConfig = new OnlineModelConfig();
        modelConfig.setTransducer(transducer);
        modelConfig.setTokens(spec.getTokens());
        modelConfig.setNumThreads(spec.getNumThreads());
        modelConfig.setProvider("cpu");
        if (!spec.getModelType().isEmpty()) {
            modelConfig.setModelType(spec.getModelType());
        }

        OnlineRecognizerConfig recognizerConfig = new OnlineRecognizerConfig();
        recognizerConfig.setFeatConfig(featureConfig);
        recognizerConfig.setModelConfig(modelConfig);
        recognizerConfig.setEndpointConfig(new EndpointConfig());
        recognizerConfig.setEnableEndpoint(true);
        recognizerConfig.setDecodingMethod("greedy_search");

        OnlineRecognizer recognizer = new OnlineRecognizer(null, recognizerConfig);
        Log.i(TAG, "Model loaded");
        return new SherpaStreamingRecognizer(recognizer);
    }
}
