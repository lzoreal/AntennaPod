package de.danoeh.antennapod.ui.transcript;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mockStatic;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.storage.database.DBReader;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;

@RunWith(RobolectricTestRunner.class)
public class TranscriptFloatingWindowServiceTest {
    @Test
    public void cancelledLoadDoesNotOverwriteCurrentFeed() throws Exception {
        FeedMedia firstMedia = media(1, 101);
        FeedMedia secondMedia = media(2, 202);
        List<Runnable> scheduledTasks = new ArrayList<>();
        Executor executor = scheduledTasks::add;
        TranscriptFloatingWindowService service = Robolectric.buildService(
                TranscriptFloatingWindowService.class).get();
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.from(executor));
        RxAndroidPlugins.setMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());

        try (MockedStatic<DBReader> dbReader = mockStatic(DBReader.class);
             MockedStatic<TranscriptUtils> transcriptUtils = mockStatic(TranscriptUtils.class)) {
            dbReader.when(() -> DBReader.getFeedMedia(1)).thenAnswer(invocation -> {
                loadTranscript(service, 2);
                scheduledTasks.get(1).run();
                return firstMedia;
            });
            dbReader.when(() -> DBReader.getFeedMedia(2)).thenReturn(secondMedia);
            transcriptUtils.when(() -> TranscriptUtils.loadTranscript(any(FeedMedia.class), anyBoolean()))
                    .thenReturn(transcript());

            loadTranscript(service, 1);
            scheduledTasks.get(0).run();

            assertEquals(202, currentFeedId(service));
        } finally {
            RxJavaPlugins.reset();
            RxAndroidPlugins.reset();
        }
    }

    private static FeedMedia media(long id, long feedId) {
        FeedItem item = new FeedItem();
        item.setId(id);
        item.setFeedId(feedId);
        FeedMedia media = new FeedMedia(item, "https://example.com/audio.mp3", 1, "audio/mpeg");
        media.setId(id);
        return media;
    }

    private static Transcript transcript() {
        Transcript transcript = new Transcript();
        transcript.addSegment(new TranscriptSegment(0, 1000, "Hello", ""));
        return transcript;
    }

    private static void loadTranscript(TranscriptFloatingWindowService service, long mediaId)
            throws Exception {
        Method method = TranscriptFloatingWindowService.class.getDeclaredMethod(
                "loadTranscript", long.class);
        method.setAccessible(true);
        method.invoke(service, mediaId);
    }

    private static long currentFeedId(TranscriptFloatingWindowService service) throws Exception {
        Field field = TranscriptFloatingWindowService.class.getDeclaredField("currentFeedId");
        field.setAccessible(true);
        return field.getLong(service);
    }
}
