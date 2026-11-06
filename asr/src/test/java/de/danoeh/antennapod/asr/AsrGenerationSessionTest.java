package de.danoeh.antennapod.asr;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AsrGenerationSessionTest {
    private static final long CHUNK_MS = 100;

    private final AudioSource source = () -> {
        throw new UnsupportedOperationException("not used");
    };

    @Test
    public void decodesUntilEndAndEmitsEverySegment() throws IOException {
        FakeDecoder decoder = new FakeDecoder(3000);
        PreReadPcmPipeline pipeline = new PreReadPcmPipeline(decoder, 1000, 200);
        pipeline.open(source);
        FakeRecognizer recognizer = new FakeRecognizer(false);
        AtomicInteger calls = new AtomicInteger();
        LongSupplier playhead = () -> calls.getAndIncrement() < 12 ? 0 : 5000;
        RecordingListener listener = new RecordingListener();

        new AsrGenerationSession(pipeline, recognizer, playhead, listener, 1).run();

        assertEquals(30, listener.segments.size());
        assertTrue(listener.completed);
        assertNull(listener.error);
        assertTrue(recognizer.closed);
        assertTrue(decoder.closed);
    }

    @Test
    public void decoderFailureIsReportedAndResourcesAreClosed() throws IOException {
        FakeDecoder decoder = new FakeDecoder(3000);
        decoder.failAtRead = 3;
        PreReadPcmPipeline pipeline = new PreReadPcmPipeline(decoder, 1000, 200);
        pipeline.open(source);
        FakeRecognizer recognizer = new FakeRecognizer(false);
        RecordingListener listener = new RecordingListener();

        new AsrGenerationSession(pipeline, recognizer, () -> 0, listener, 1).run();

        assertNotNull(listener.error);
        assertFalse(listener.completed);
        assertTrue(recognizer.closed);
        assertTrue(decoder.closed);
    }

    @Test
    public void retryableDecoderFailureIsRetriedInsteadOfStopping() throws IOException {
        FakeDecoder decoder = new FakeDecoder(3000);
        decoder.retryableFailures = 2;
        PreReadPcmPipeline pipeline = new PreReadPcmPipeline(decoder, 1000, 200);
        pipeline.open(source);
        FakeRecognizer recognizer = new FakeRecognizer(false);
        AtomicInteger calls = new AtomicInteger();
        LongSupplier playhead = () -> calls.getAndIncrement() < 12 ? 0 : 5000;
        RecordingListener listener = new RecordingListener();

        new AsrGenerationSession(pipeline, recognizer, playhead, listener, 1).run();

        assertEquals(30, listener.segments.size());
        assertTrue(listener.completed);
        assertNull(listener.error);
    }

    @Test
    public void stopFlushesThePartialSegment() throws Exception {
        FakeDecoder decoder = new FakeDecoder(60000);
        PreReadPcmPipeline pipeline = new PreReadPcmPipeline(decoder, 1000, 200);
        pipeline.open(source);
        FakeRecognizer recognizer = new FakeRecognizer(true);
        RecordingListener listener = new RecordingListener();
        AtomicLong playhead = new AtomicLong(0);
        AsrGenerationSession session = new AsrGenerationSession(pipeline, recognizer,
                playhead::get, listener, 5);

        Thread thread = new Thread(session::run);
        thread.start();
        assertTrue(listener.caughtUpLatch.await(2, TimeUnit.SECONDS));
        session.stop();
        thread.join(2000);

        assertFalse(thread.isAlive());
        // A stop already reports STOPPED through the manager; completing as well would
        // mark the wrong media as finished when the user switched episodes.
        assertFalse(listener.completed);
        assertEquals(1, listener.segments.size());
        assertTrue(recognizer.flushed);
        assertTrue(recognizer.closed);
    }

    @Test
    public void stopDuringRetryBackoffFlushesAndDoesNotReportAnError() throws Exception {
        FakeDecoder decoder = new FakeDecoder(60000);
        decoder.retryableFailures = 1;
        PreReadPcmPipeline pipeline = new PreReadPcmPipeline(decoder, 1000, 200);
        pipeline.open(source);
        FakeRecognizer recognizer = new FakeRecognizer(false);
        RecordingListener listener = new RecordingListener();
        AsrGenerationSession session = new AsrGenerationSession(pipeline, recognizer,
                () -> 0, listener, 5);

        Thread thread = new Thread(session::run);
        thread.start();
        assertTrue(decoder.retryStarted.await(2, TimeUnit.SECONDS));
        session.stop();
        thread.join(2000);

        assertFalse(thread.isAlive());
        assertNull(listener.error);
        assertFalse(listener.completed);
        assertTrue(recognizer.flushed);
        assertTrue(recognizer.closed);
        assertTrue(decoder.closed);
    }

    private static final class FakeDecoder implements PcmDecoder {
        private final long durationMs;
        private final CountDownLatch retryStarted = new CountDownLatch(1);
        private long position;
        private int reads;
        private int failAtRead = -1;
        private int retryableFailures;
        private boolean closed;

        FakeDecoder(long durationMs) {
            this.durationMs = durationMs;
        }

        @Override
        public void open(AudioSource source, long startTimeMs) {
            position = startTimeMs;
        }

        @Override
        public void seekTo(long timeMs) {
            position = timeMs;
        }

        @Override
        public PcmChunk read() throws IOException {
            reads++;
            if (reads == failAtRead) {
                throw new IOException("decoder failed");
            }
            if (retryableFailures > 0) {
                retryableFailures--;
                retryStarted.countDown();
                throw new AsrRetryableException("cache still filling");
            }
            if (position >= durationMs) {
                return null;
            }
            PcmChunk chunk = new PcmChunk(new float[1600], 1600, position);
            position += CHUNK_MS;
            return chunk;
        }

        @Override
        public long durationMs() {
            return durationMs;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeRecognizer implements StreamingAsr {
        private final boolean emitOnFlushOnly;
        private final List<AsrSegment> segments = new ArrayList<>();
        private boolean flushed;
        private boolean closed;
        private long lastEndMs;

        FakeRecognizer(boolean emitOnFlushOnly) {
            this.emitOnFlushOnly = emitOnFlushOnly;
        }

        @Override
        public void accept(PcmChunk chunk) {
            lastEndMs = chunk.endTimeMs();
            if (!emitOnFlushOnly) {
                segments.add(segment(chunk.startTimeMs(), chunk.endTimeMs()));
            }
        }

        @Override
        public AsrSegment pollSegment() {
            return segments.isEmpty() ? null : segments.remove(0);
        }

        @Override
        public void flush() {
            flushed = true;
            if (emitOnFlushOnly) {
                segments.add(segment(0, CHUNK_MS));
            }
        }

        @Override
        public long lastProcessedMs() {
            return lastEndMs;
        }

        @Override
        public void close() {
            closed = true;
        }

        private static AsrSegment segment(long startMs, long endMs) {
            return new AsrSegment("w", Collections.singletonList(
                    new AsrWord("w", startMs, endMs, 0, 1)));
        }
    }

    private static final class RecordingListener implements AsrGenerationSession.Listener {
        private final List<AsrSegment> segments = new ArrayList<>();
        private final CountDownLatch caughtUpLatch = new CountDownLatch(1);
        private boolean completed;
        private Throwable error;

        @Override
        public void onSegment(AsrSegment segment) {
            segments.add(segment);
        }

        @Override
        public void onCaughtUp(long decodedEndMs) {
            caughtUpLatch.countDown();
        }

        @Override
        public void onCompleted() {
            completed = true;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }
    }
}
