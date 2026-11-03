package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

import de.danoeh.antennapod.ad.StreamingAdScanner;

/** Wraps the audio sink with a PCM tap feeding the streaming ad scanner. */
@UnstableApi
public final class PcmTapRenderersFactory extends DefaultRenderersFactory {
    private final StreamingAdScanner scanner;
    private final PcmTapProcessor.PositionSource position;

    public PcmTapRenderersFactory(Context context, StreamingAdScanner scanner,
                                  PcmTapProcessor.PositionSource position) {
        super(context);
        this.scanner = scanner;
        this.position = position;
    }

    @Override
    protected @Nullable AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                                                 boolean enableAudioTrackPlaybackParams) {
        return new DefaultAudioSink.Builder(context)
                .setAudioProcessors(new AudioProcessor[]{PcmTapProcessor.create(scanner, position)})
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build();
    }
}
