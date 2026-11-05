package de.danoeh.antennapod.ui.screen.playback;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.Nullable;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.databinding.TranscriptPageFragmentBinding;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

/** Owns the Aa panel state and the layout metrics applied to the transcript list. */
final class TranscriptLayoutController {
    private static final int FONT_SIZE_MIN_STEP = UserPreferences.TRANSCRIPT_FONT_SIZE_MIN_STEP;
    private static final int FONT_SIZE_MAX_STEP = UserPreferences.TRANSCRIPT_FONT_SIZE_MAX_STEP;
    private static final int[] FONT_SIZE_LABELS = {
            R.string.transcript_layout_font_tiny,
            R.string.transcript_layout_font_small,
            R.string.transcript_layout_standard,
            R.string.transcript_layout_font_large,
            R.string.transcript_layout_font_larger,
            R.string.transcript_layout_font_largest};
    private static final int[] LINE_SPACING_LABELS = {
            R.string.transcript_layout_compact,
            R.string.transcript_layout_standard,
            R.string.transcript_layout_loose};
    private static final int[] SIDE_MARGIN_LABELS = {
            R.string.transcript_layout_narrow,
            R.string.transcript_layout_standard,
            R.string.transcript_layout_wide};
    private static final int[] VERTICAL_SPACING_LABELS = {
            R.string.transcript_layout_compact,
            R.string.transcript_layout_standard,
            R.string.transcript_layout_loose};

    interface Host {
        @Nullable
        Context getContext();

        @Nullable
        TranscriptAdapter getAdapter();
    }

    private final Host host;
    @Nullable
    private TranscriptPageFragmentBinding binding;
    private int appliedLayoutSignature = Integer.MIN_VALUE;
    private int appliedLayoutHeight = -1;
    private int appliedLayoutWidth = -1;
    private int followBottomSpace;

    TranscriptLayoutController(Host host) {
        this.host = host;
    }

    void attach(TranscriptPageFragmentBinding viewBinding) {
        binding = viewBinding;
        setupSteppers();
    }

    void detach() {
        binding = null;
    }

    int getFollowBottomSpace() {
        return followBottomSpace;
    }

    private void setupSteppers() {
        if (binding == null) {
            return;
        }
        binding.btnFontSizeDown.setOnClickListener(v -> adjustFontSize(-1));
        binding.btnFontSizeUp.setOnClickListener(v -> adjustFontSize(1));
        bindStepper(binding.btnLineSpacingDown, binding.btnLineSpacingUp,
                R.string.transcript_layout_line_spacing);
        bindStepper(binding.btnSideMarginsDown, binding.btnSideMarginsUp,
                R.string.transcript_layout_side_margins);
        bindStepper(binding.btnVerticalSpacingDown, binding.btnVerticalSpacingUp,
                R.string.transcript_layout_vertical_spacing);
        binding.btnLineSpacingDown.setOnClickListener(v -> adjustLineSpacing(-1));
        binding.btnLineSpacingUp.setOnClickListener(v -> adjustLineSpacing(1));
        binding.btnSideMarginsDown.setOnClickListener(v -> adjustSideMargins(-1));
        binding.btnSideMarginsUp.setOnClickListener(v -> adjustSideMargins(1));
        binding.btnVerticalSpacingDown.setOnClickListener(v -> adjustVerticalSpacing(-1));
        binding.btnVerticalSpacingUp.setOnClickListener(v -> adjustVerticalSpacing(1));
        binding.btnLayoutReset.setOnClickListener(v -> reset());
    }

    private void bindStepper(TextView decrease, TextView increase, int labelRes) {
        Context context = host.getContext();
        if (context == null) {
            return;
        }
        String label = context.getString(labelRes);
        decrease.setContentDescription(context.getString(R.string.transcript_layout_decrease)
                + " " + label);
        increase.setContentDescription(context.getString(R.string.transcript_layout_increase)
                + " " + label);
    }

    void updateControls() {
        Context context = host.getContext();
        TranscriptAdapter adapter = host.getAdapter();
        if (binding == null || adapter == null || context == null) {
            return;
        }
        int fontStep = adapter.getFontSizeStep();
        binding.txtFontSizeValue.setText(FONT_SIZE_LABELS[fontStep - FONT_SIZE_MIN_STEP]);
        int lineStep = UserPreferences.getTranscriptLineSpacingStep();
        binding.txtLineSpacingValue.setText(LINE_SPACING_LABELS[lineStep]);
        int marginStep = UserPreferences.getTranscriptSideMarginsStep();
        binding.txtSideMarginsValue.setText(SIDE_MARGIN_LABELS[marginStep]);
        int verticalStep = UserPreferences.getTranscriptVerticalSpacingStep();
        binding.txtVerticalSpacingValue.setText(VERTICAL_SPACING_LABELS[verticalStep]);

        binding.btnFontSizeDown.setAlpha(fontStep > FONT_SIZE_MIN_STEP ? 1.0f : 0.5f);
        binding.btnFontSizeUp.setAlpha(fontStep < FONT_SIZE_MAX_STEP ? 1.0f : 0.5f);
        binding.btnLineSpacingDown.setAlpha(
                lineStep > UserPreferences.TRANSCRIPT_LAYOUT_MIN_STEP ? 1.0f : 0.5f);
        binding.btnLineSpacingUp.setAlpha(
                lineStep < UserPreferences.TRANSCRIPT_LAYOUT_MAX_STEP ? 1.0f : 0.5f);
        binding.btnSideMarginsDown.setAlpha(
                marginStep > UserPreferences.TRANSCRIPT_LAYOUT_MIN_STEP ? 1.0f : 0.5f);
        binding.btnSideMarginsUp.setAlpha(
                marginStep < UserPreferences.TRANSCRIPT_LAYOUT_MAX_STEP ? 1.0f : 0.5f);
        binding.btnVerticalSpacingDown.setAlpha(
                verticalStep > UserPreferences.TRANSCRIPT_LAYOUT_MIN_STEP ? 1.0f : 0.5f);
        binding.btnVerticalSpacingUp.setAlpha(
                verticalStep < UserPreferences.TRANSCRIPT_LAYOUT_MAX_STEP ? 1.0f : 0.5f);
        binding.btnLayout.setAlpha(
                binding.layoutControls.getVisibility() == View.VISIBLE ? 1.0f : 0.5f);
    }

    void applyLayout() {
        Context context = host.getContext();
        TranscriptAdapter adapter = host.getAdapter();
        if (binding == null || context == null) {
            return;
        }
        int padding = Math.round(UserPreferences.getTranscriptVerticalSpacingDp()
                * context.getResources().getDisplayMetrics().density);
        // This also runs from the layout-change listener. Keep it idempotent: touching the
        // padding or calling notifyDataSetChanged on every layout pass kept the page in a
        // height oscillation (2327/1696), and each pass invalidated every ViewHolder position
        // (getAdapterPosition() == -1), which silently killed the follow after a new ASR
        // segment was appended.
        int signature = UserPreferences.getTranscriptFontSize() * 1000000
                + UserPreferences.getTranscriptLineSpacingStep() * 10000
                + UserPreferences.getTranscriptSideMarginsStep() * 100
                + UserPreferences.getTranscriptVerticalSpacingStep();
        if (signature != appliedLayoutSignature) {
            appliedLayoutSignature = signature;
            binding.recyclerView.setPadding(0, padding, 0, padding);
            if (adapter != null) {
                adapter.refreshLayout();
            }
        }
        int width = binding.recyclerView.getWidth();
        if (width > 0 && width != appliedLayoutWidth) {
            appliedLayoutWidth = width;
            if (adapter != null) {
                adapter.setAvailableWidth(width);
            }
        }
        // A footer gap extends the scrollable content without shrinking the visible viewport,
        // so the last rows can still reach the 40% reading line (bottom padding cannot).
        int height = binding.recyclerView.getHeight();
        int bottomSpace = height > 0
                ? Math.round(height * (1f - TranscriptFollowMotion.ANCHOR_RATIO)) : 0;
        if (height != appliedLayoutHeight || bottomSpace != followBottomSpace) {
            appliedLayoutHeight = height;
            followBottomSpace = bottomSpace;
            binding.recyclerView.invalidateItemDecorations();
        }
    }

    private void adjustLineSpacing(int delta) {
        UserPreferences.setTranscriptLineSpacingStep(
                UserPreferences.getTranscriptLineSpacingStep() + delta);
        applyLayout();
        updateControls();
    }

    private void adjustSideMargins(int delta) {
        UserPreferences.setTranscriptSideMarginsStep(
                UserPreferences.getTranscriptSideMarginsStep() + delta);
        applyLayout();
        updateControls();
    }

    private void adjustVerticalSpacing(int delta) {
        UserPreferences.setTranscriptVerticalSpacingStep(
                UserPreferences.getTranscriptVerticalSpacingStep() + delta);
        applyLayout();
        updateControls();
    }

    private void reset() {
        TranscriptAdapter adapter = host.getAdapter();
        if (adapter == null) {
            return;
        }
        adapter.setFontSizeStep(0);
        UserPreferences.setTranscriptLineSpacingStep(
                UserPreferences.TRANSCRIPT_LINE_SPACING_DEFAULT_STEP);
        UserPreferences.setTranscriptSideMarginsStep(
                UserPreferences.TRANSCRIPT_SIDE_MARGINS_DEFAULT_STEP);
        UserPreferences.setTranscriptVerticalSpacingStep(
                UserPreferences.TRANSCRIPT_VERTICAL_SPACING_DEFAULT_STEP);
        applyLayout();
        updateControls();
    }

    private void adjustFontSize(int delta) {
        TranscriptAdapter adapter = host.getAdapter();
        if (adapter == null) {
            return;
        }
        int step = adapter.getFontSizeStep();
        int next = Math.max(FONT_SIZE_MIN_STEP, Math.min(FONT_SIZE_MAX_STEP, step + delta));
        if (next != step) {
            adapter.setFontSizeStep(next);
            updateControls();
        }
    }
}
