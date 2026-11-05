package de.danoeh.antennapod.ui.screen.playback;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.snackbar.Snackbar;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.databinding.TranscriptPageFragmentBinding;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.preferences.TranscriptTimeOffsets;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import java.util.Locale;

/** Owns the offset bar, pick-to-align mode, anchors and their undo actions. */
final class TranscriptOffsetController {
    private static final long SAME_OFFSET_TOLERANCE_MS = 1000;

    interface Host {
        @Nullable
        Context getContext();

        @Nullable
        FeedMedia getMedia();

        @Nullable
        TranscriptAdapter getAdapter();

        long getLastPositionMs();

        void setLastPositionMs(long positionMs);

        long currentPlaybackPosition();

        void setUserScrolling(boolean scrolling);

        void updateBackToCurrentVisibility();

        void resetScrolledPosition();

        void scrollToPosition(int position, boolean snap);

        void showToast(int resId);

        void setToggleState(View view, int descriptionRes, boolean on);

        void onLayoutPanelStateChanged();
    }

    private final Host host;
    @Nullable
    private TranscriptPageFragmentBinding binding;
    private boolean syncPickMode;
    private long lastLabelMs = Long.MIN_VALUE;

    TranscriptOffsetController(Host host) {
        this.host = host;
    }

    void attach(TranscriptPageFragmentBinding viewBinding) {
        binding = viewBinding;
        setupButtons();
    }

    void detach() {
        binding = null;
    }

    boolean isSyncPickMode() {
        return syncPickMode;
    }

    void setupButtons() {
        Context context = host.getContext();
        if (binding == null || context == null) {
            return;
        }
        binding.btnOffsetSync.setContentDescription(
                context.getString(R.string.transcript_offset_sync_current_description));
        binding.btnOffsetSync.setOnClickListener(v -> toggleSyncPickMode());
        binding.btnOffsetSync.setOnLongClickListener(v -> {
            confirmAlignFirstSentence();
            return true;
        });
        bindFineButton(binding.btnOffsetBack05, -500);
        bindFineButton(binding.btnOffsetForward05, 500);
        bindButton(binding.btnOffsetBack30, -30000);
        bindButton(binding.btnOffsetBack10, -10000);
        bindButton(binding.btnOffsetBack5, -5000);
        bindButton(binding.btnOffsetForward5, 5000);
        bindButton(binding.btnOffsetForward10, 10000);
        bindButton(binding.btnOffsetForward30, 30000);
    }

    void toggleBar() {
        if (binding == null) {
            return;
        }
        boolean visible = binding.offsetControls.getVisibility() == View.VISIBLE;
        if (visible) {
            setSyncPickMode(false);
        }
        binding.layoutControls.setVisibility(View.GONE);
        binding.moreControls.setVisibility(View.GONE);
        binding.btnMore.setAlpha(0.5f);
        binding.offsetControls.setVisibility(visible ? View.GONE : View.VISIBLE);
        updateToggle();
        host.onLayoutPanelStateChanged();
        updateLabel();
    }

    void closePanels() {
        setSyncPickMode(false);
        if (binding != null) {
            binding.offsetControls.setVisibility(View.GONE);
        }
        updateToggle();
    }

    void updateToggle() {
        if (binding == null) {
            return;
        }
        boolean visible = binding.offsetControls.getVisibility() == View.VISIBLE;
        binding.btnOffsetToggle.setAlpha(visible ? 1.0f : 0.5f);
        host.setToggleState(binding.btnOffsetToggle,
                R.string.transcript_offset_toggle_description, visible);
    }

    void updateAvailability() {
        if (binding == null) {
            return;
        }
        FeedMedia media = host.getMedia();
        boolean hide = media == null || media.getTranscript() == null;
        binding.btnOffsetToggle.setVisibility(hide ? View.GONE : View.VISIBLE);
        if (hide) {
            setSyncPickMode(false);
            binding.offsetControls.setVisibility(View.GONE);
        }
    }

    long clamp(long offsetMs) {
        FeedMedia media = host.getMedia();
        if (media == null) {
            return offsetMs;
        }
        int duration = media.getDuration();
        if (duration > 0) {
            offsetMs = Math.max(-duration, Math.min(duration, offsetMs));
        }
        return offsetMs;
    }

    void adjust(long deltaMs) {
        FeedMedia media = host.getMedia();
        if (media == null) {
            return;
        }
        long position = host.getLastPositionMs();
        long offsetMs = clamp(UserPreferences.getTranscriptTimeOffset(media.getId(), position)
                + deltaMs);
        UserPreferences.updateTranscriptTimeOffset(media.getId(), position, offsetMs);
        applyChange(offsetMs, R.string.transcript_offset_message, true);
    }

    void reset() {
        FeedMedia media = host.getMedia();
        if (media == null) {
            return;
        }
        UserPreferences.removeTranscriptTimeOffset(media.getId());
        applyChange(0, R.string.transcript_offset_message, true);
    }

    private void toggleSyncPickMode() {
        if (syncPickMode) {
            setSyncPickMode(false);
            return;
        }
        FeedMedia media = host.getMedia();
        Context context = host.getContext();
        if (media == null || media.getTranscript() == null
                || media.getTranscript().getSegmentCount() == 0) {
            return;
        }
        setSyncPickMode(true);
        if (context != null) {
            Toast.makeText(context, R.string.transcript_offset_sync_pick_hint,
                    Toast.LENGTH_LONG).show();
        }
    }

    void setSyncPickMode(boolean picking) {
        syncPickMode = picking;
        Context context = host.getContext();
        if (binding == null || context == null) {
            return;
        }
        binding.pickModeBanner.setVisibility(picking ? View.VISIBLE : View.GONE);
        binding.btnOffsetSync.setText(picking
                ? R.string.transcript_offset_sync_picking
                : R.string.transcript_offset_sync_current);
        binding.btnOffsetSync.setContentDescription(context.getString(picking
                ? R.string.transcript_offset_sync_picking_description
                : R.string.transcript_offset_sync_current_description));
    }

    void onPickedSentence(int tappedIndex, long sentenceStartTime, long sentenceEndTime) {
        FeedMedia media = host.getMedia();
        Context context = host.getContext();
        if (media == null || media.getTranscript() == null || context == null) {
            return;
        }
        long mediaId = media.getId();
        long position = host.currentPlaybackPosition();
        long currentOffset = UserPreferences.getTranscriptTimeOffset(mediaId, position);
        long shownCueTime = position - currentOffset;
        long offsetMs = clamp(position - sentenceStartTime);
        // Picking the line that is already highlighted cannot fix a large shift: either it is the
        // sentence shown right now, or it maps to (almost) the offset that is already in use.
        boolean alreadyShown = tappedIndex
                == media.getTranscript().findSegmentIndexBefore(shownCueTime)
                && sentenceStartTime <= shownCueTime && shownCueTime < sentenceEndTime;
        if (alreadyShown || Math.abs(offsetMs - currentOffset) < SAME_OFFSET_TOLERANCE_MS) {
            Toast.makeText(context, R.string.transcript_offset_sync_already_aligned,
                    Toast.LENGTH_LONG).show();
            return;
        }
        host.setLastPositionMs(position);
        setSyncPickMode(false);
        TranscriptTimeOffsets previous = UserPreferences.getTranscriptTimeOffsetsForMedia(mediaId);
        UserPreferences.setTranscriptTimeOffset(mediaId, position, offsetMs);
        applyChange(offsetMs, R.string.transcript_offset_sync_message, false);
        showUndo(R.string.transcript_offset_sync_message, offsetMs, previous);
    }

    private void alignFirstSentence() {
        FeedMedia media = host.getMedia();
        if (media == null || media.getTranscript() == null
                || media.getTranscript().getSegmentCount() == 0) {
            return;
        }
        long mediaId = media.getId();
        TranscriptTimeOffsets previous = UserPreferences.getTranscriptTimeOffsetsForMedia(mediaId);
        long position = host.currentPlaybackPosition();
        long offsetMs = clamp(position - media.getTranscript().getSegmentAt(0).getStartTime());
        host.setLastPositionMs(position);
        setSyncPickMode(false);
        UserPreferences.setTranscriptStartOffset(mediaId, offsetMs);
        applyChange(offsetMs, R.string.transcript_offset_sync_first_message, false);
        showUndo(R.string.transcript_offset_sync_first_message, offsetMs, previous);
    }

    /** Long press replaces every anchor of the episode. Ask first when there is anything to lose. */
    private void confirmAlignFirstSentence() {
        FeedMedia media = host.getMedia();
        Context context = host.getContext();
        if (media == null || media.getTranscript() == null
                || media.getTranscript().getSegmentCount() == 0 || context == null) {
            return;
        }
        int anchorCount = UserPreferences.getTranscriptTimeOffsetsForMedia(media.getId())
                .getAnchors().size();
        if (anchorCount == 0) {
            alignFirstSentence();
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.transcript_offset_align_first_confirm_title)
                .setMessage(context.getString(
                        R.string.transcript_offset_align_first_confirm_message, anchorCount))
                .setPositiveButton(R.string.transcript_offset_align_first_confirm,
                        (dialog, which) -> alignFirstSentence())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showUndo(int messageRes, long offsetMs, TranscriptTimeOffsets previous) {
        Context context = host.getContext();
        if (binding == null || context == null) {
            return;
        }
        Snackbar.make(binding.getRoot(),
                        context.getString(messageRes, format(offsetMs)),
                        Snackbar.LENGTH_LONG)
                .setAction(R.string.undo, v -> {
                    FeedMedia media = host.getMedia();
                    if (media == null) {
                        return;
                    }
                    UserPreferences.setTranscriptTimeOffsetsForMedia(media.getId(), previous);
                    applyChange(UserPreferences.getTranscriptTimeOffset(
                                    media.getId(), host.getLastPositionMs()),
                            R.string.transcript_offset_restored, true);
                })
                .show();
    }

    private void applyChange(long offsetMs, int messageRes, boolean showToast) {
        FeedMedia media = host.getMedia();
        TranscriptAdapter adapter = host.getAdapter();
        long position = host.getLastPositionMs();
        if (adapter != null && media != null && media.getTranscript() != null
                && media.getTranscript().getSegmentCount() > 0) {
            int pos = media.getTranscript().findSegmentIndexBefore(position - offsetMs);
            adapter.setHighlightPosition(pos, position - offsetMs);
            host.setUserScrolling(false);
            host.updateBackToCurrentVisibility();
            host.resetScrolledPosition();
            host.scrollToPosition(pos, true);
        }
        updateLabel();
        Context context = host.getContext();
        if (showToast && context != null) {
            Toast.makeText(context, context.getString(messageRes, format(offsetMs)),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** The panel shows the offset of the segment playing now, which changes at every anchor. */
    void refreshLabelIfVisible() {
        if (binding == null || binding.offsetControls.getVisibility() != View.VISIBLE) {
            return;
        }
        FeedMedia media = host.getMedia();
        if (media == null) {
            return;
        }
        long offsetMs = UserPreferences.getTranscriptTimeOffset(
                media.getId(), host.getLastPositionMs());
        if (offsetMs != lastLabelMs) {
            updateLabel();
        }
    }

    void updateLabel() {
        Context context = host.getContext();
        if (binding == null || context == null) {
            return;
        }
        FeedMedia media = host.getMedia();
        long offsetMs = media == null ? 0
                : UserPreferences.getTranscriptTimeOffset(media.getId(), host.getLastPositionMs());
        lastLabelMs = offsetMs;
        binding.txtOffsetValue.setText(context.getString(
                R.string.transcript_offset_label, format(offsetMs)));
        binding.txtOffsetValue.setContentDescription(
                context.getString(R.string.transcript_offset_reset_description));
        boolean hasOffset = media != null && UserPreferences.hasTranscriptTimeOffset(media.getId());
        binding.btnOffsetReset.setEnabled(hasOffset);
        binding.btnOffsetReset.setAlpha(hasOffset ? 1.0f : 0.5f);
    }

    private String format(long offsetMs) {
        Context context = host.getContext();
        double seconds = offsetMs / 1000.0;
        String value = seconds == Math.rint(seconds)
                ? String.valueOf((long) seconds)
                : String.format(Locale.getDefault(), "%.1f", seconds);
        return context == null ? value
                : context.getString(R.string.transcript_offset_value_format,
                        (seconds > 0 ? "+" : "") + value);
    }

    private void bindFineButton(TextView button, long deltaMs) {
        Context context = host.getContext();
        String seconds = String.format(Locale.getDefault(), "%.1f", Math.abs(deltaMs) / 1000.0);
        boolean backwards = deltaMs < 0;
        if (context != null) {
            button.setText(context.getString(backwards
                    ? R.string.transcript_offset_back_fine_format
                    : R.string.transcript_offset_forward_fine_format, seconds));
            button.setContentDescription(context.getString(backwards
                    ? R.string.transcript_offset_back_fine_description
                    : R.string.transcript_offset_forward_fine_description, seconds));
        }
        button.setOnClickListener(v -> adjust(deltaMs));
    }

    private void bindButton(TextView button, long deltaMs) {
        Context context = host.getContext();
        int seconds = (int) Math.abs(deltaMs / 1000);
        boolean backwards = deltaMs < 0;
        if (context != null) {
            button.setText(context.getString(backwards
                    ? R.string.transcript_offset_back_format
                    : R.string.transcript_offset_forward_format, seconds));
            button.setContentDescription(context.getResources().getQuantityString(backwards
                    ? R.plurals.transcript_offset_back_description
                    : R.plurals.transcript_offset_forward_description, seconds, seconds));
        }
        button.setOnClickListener(v -> adjust(deltaMs));
    }
}
