package de.danoeh.antennapod.ui.screen.playback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.databinding.TranscriptPageFragmentBinding;
import de.danoeh.antennapod.ui.transcript.TranscriptViewholder;

/** Owns the in-transcript search bar, debounce, hit navigation and scroll positioning. */
final class TranscriptSearchController {
    private static final long SEARCH_DEBOUNCE_MS = 200;
    private static final int SEARCH_SCROLL_MAX_ATTEMPTS = 20;

    interface Host {
        @Nullable
        Context getContext();

        @Nullable
        TranscriptAdapter getAdapter();

        void hideControlPanels();

        void stopFollowFrame();

        void scheduleFollowFrame();

        void setUserScrolling(boolean scrolling);

        void setPendingSnapToHighlight(boolean pending);

        void updateBackToCurrentVisibility();

        void scrollToPosition(int position, boolean snap);

        int readingLinePx(RecyclerView recyclerView);
    }

    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable
    private TranscriptPageFragmentBinding binding;
    private boolean active;
    private ViewTreeObserver.OnPreDrawListener scrollListener;
    private int scrollPosition = -1;
    private int scrollIndex = -1;
    private int scrollAttempts = 0;
    private final Runnable searchRunnable = () -> {
        if (binding != null) {
            runSearch(binding.searchInput.getText().toString());
        }
    };

    TranscriptSearchController(Host host) {
        this.host = host;
    }

    void attach(TranscriptPageFragmentBinding viewBinding) {
        binding = viewBinding;
        setupControls();
    }

    void pause() {
        handler.removeCallbacks(searchRunnable);
    }

    void detach() {
        handler.removeCallbacks(searchRunnable);
        if (binding != null) {
            stopScrollFrame(binding.recyclerView);
        }
        scrollListener = null;
        active = false;
        binding = null;
    }

    boolean isActive() {
        return active;
    }

    String getQuery() {
        return binding == null ? "" : binding.searchInput.getText().toString();
    }

    private void setupControls() {
        if (binding == null) {
            return;
        }
        binding.btnSearch.setOnClickListener(v -> toggle());
        binding.btnSearchClose.setOnClickListener(v -> close());
        binding.btnSearchPrev.setOnClickListener(v -> move(false));
        binding.btnSearchNext.setOnClickListener(v -> move(true));
        binding.searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                handler.removeCallbacks(searchRunnable);
                handler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS);
            }
        });
        binding.searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                move(true);
                return true;
            }
            return false;
        });
        binding.btnSearch.setAlpha(0.5f);
        updateControls();
    }

    void toggle() {
        if (binding == null) {
            return;
        }
        if (active) {
            close();
            return;
        }
        host.hideControlPanels();
        active = true;
        binding.searchBar.setVisibility(View.VISIBLE);
        binding.btnSearch.setAlpha(1.0f);
        binding.searchInput.requestFocus();
        showKeyboard();
        if (!getQuery().trim().isEmpty()) {
            runSearch(getQuery());
        }
        updateControls();
    }

    void close() {
        if (binding == null) {
            return;
        }
        handler.removeCallbacks(searchRunnable);
        hideKeyboard();
        binding.searchInput.clearFocus();
        binding.searchInput.setText("");
        TranscriptAdapter adapter = host.getAdapter();
        if (adapter != null) {
            adapter.clearSearch();
        }
        active = false;
        binding.searchBar.setVisibility(View.GONE);
        binding.btnSearch.setAlpha(0.5f);
        updateControls();
        // Search positioning pauses follow. Closing it must hand the list back to the reader.
        stopScrollFrame(binding.recyclerView);
        host.setUserScrolling(false);
        host.setPendingSnapToHighlight(true);
        host.updateBackToCurrentVisibility();
        if (adapter != null) {
            int position = adapter.getHighlightPosition();
            if (position >= 0) {
                host.scrollToPosition(position, true);
            } else {
                host.scheduleFollowFrame();
            }
        }
    }

    void restoreState(String query, int hit) {
        if (binding == null || query.trim().isEmpty()) {
            return;
        }
        active = true;
        binding.searchBar.setVisibility(View.VISIBLE);
        binding.btnSearch.setAlpha(1.0f);
        binding.searchInput.setText(query);
        handler.removeCallbacks(searchRunnable);
        runSearch(query);
        updateControls();
        TranscriptAdapter adapter = host.getAdapter();
        if (adapter != null && hit > 0 && hit < adapter.getSearchHitCount()) {
            adapter.setCurrentSearchHit(hit);
            jumpToHit(hit);
            updateControls();
        }
        binding.searchInput.clearFocus();
    }

    void runSearch(String query) {
        if (binding == null) {
            return;
        }
        TranscriptAdapter adapter = host.getAdapter();
        if (adapter == null) {
            return;
        }
        if (query.trim().isEmpty()) {
            adapter.clearSearch();
            updateControls();
            return;
        }
        int count = adapter.setSearchQuery(query);
        updateControls();
        if (count > 0) {
            jumpToHit(adapter.getCurrentSearchHit());
        }
    }

    private void updateControls() {
        if (binding == null) {
            return;
        }
        TranscriptAdapter adapter = host.getAdapter();
        Context context = host.getContext();
        if (adapter == null || context == null) {
            return;
        }
        int count = adapter.getSearchHitCount();
        boolean hasQuery = !getQuery().trim().isEmpty();
        binding.txtSearchCount.setVisibility(hasQuery ? View.VISIBLE : View.GONE);
        if (hasQuery) {
            if (count == 0) {
                binding.txtSearchCount.setText(R.string.transcript_search_no_results);
            } else {
                binding.txtSearchCount.setText(context.getString(
                        R.string.transcript_search_count,
                        adapter.getCurrentSearchHit() + 1, count));
            }
        }
        boolean enabled = count > 0;
        binding.btnSearchPrev.setEnabled(enabled);
        binding.btnSearchNext.setEnabled(enabled);
        binding.btnSearchPrev.setAlpha(enabled ? 1.0f : 0.5f);
        binding.btnSearchNext.setAlpha(enabled ? 1.0f : 0.5f);
    }

    private void move(boolean forward) {
        TranscriptAdapter adapter = host.getAdapter();
        if (adapter == null || adapter.getSearchHitCount() == 0) {
            return;
        }
        int index = adapter.stepSearch(forward);
        updateControls();
        jumpToHit(index);
    }

    private void jumpToHit(int index) {
        if (binding == null) {
            return;
        }
        TranscriptAdapter adapter = host.getAdapter();
        if (adapter == null) {
            return;
        }
        int position = adapter.getSearchHitPosition(index);
        if (position < 0) {
            return;
        }
        host.setUserScrolling(true);
        host.setPendingSnapToHighlight(false);
        host.stopFollowFrame();
        host.updateBackToCurrentVisibility();

        RecyclerView recyclerView = binding.recyclerView;
        LinearLayoutManager layoutManager =
                (LinearLayoutManager) recyclerView.getLayoutManager();
        if (layoutManager == null) {
            return;
        }
        recyclerView.stopScroll();
        int first = layoutManager.findFirstVisibleItemPosition();
        int last = layoutManager.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || position < first || position > last) {
            layoutManager.scrollToPositionWithOffset(position,
                    Math.max(0, recyclerView.getHeight() / 2));
        }
        scheduleScroll(position, index);
    }

    private void scheduleScroll(int position, int index) {
        if (binding == null) {
            return;
        }
        RecyclerView recyclerView = binding.recyclerView;
        stopScrollFrame(recyclerView);
        scrollPosition = position;
        scrollIndex = index;
        scrollAttempts = 0;
        ViewTreeObserver observer = recyclerView.getViewTreeObserver();
        if (!observer.isAlive()) {
            return;
        }
        scrollListener = () -> {
            TranscriptAdapter adapter = host.getAdapter();
            if (binding == null || adapter == null || scrollPosition < 0) {
                stopScrollFrame(recyclerView);
                return true;
            }
            RecyclerView.ViewHolder holder =
                    recyclerView.findViewHolderForAdapterPosition(scrollPosition);
            if (!(holder instanceof TranscriptViewholder)) {
                if (++scrollAttempts < SEARCH_SCROLL_MAX_ATTEMPTS) {
                    recyclerView.postInvalidateOnAnimation();
                    return true;
                }
                stopScrollFrame(recyclerView);
                return true;
            }
            int[] bounds = adapter.getSearchHitBounds(
                    (TranscriptViewholder) holder, scrollIndex);
            if (bounds == null) {
                stopScrollFrame(recyclerView);
                return true;
            }
            int viewportHeight = recyclerView.getHeight();
            if (viewportHeight <= 0) {
                stopScrollFrame(recyclerView);
                return true;
            }
            int target = holder.itemView.getTop() + (bounds[0] + bounds[1]) / 2;
            int error = target - host.readingLinePx(recyclerView);
            if ((error < 0 && !recyclerView.canScrollVertically(-1))
                    || (error > 0 && !recyclerView.canScrollVertically(1))) {
                stopScrollFrame(recyclerView);
                return true;
            }
            if (error != 0) {
                recyclerView.scrollBy(0, error);
            }
            stopScrollFrame(recyclerView);
            return true;
        };
        observer.addOnPreDrawListener(scrollListener);
        recyclerView.postInvalidateOnAnimation();
    }

    private void stopScrollFrame(RecyclerView recyclerView) {
        if (scrollListener != null) {
            ViewTreeObserver observer = recyclerView.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnPreDrawListener(scrollListener);
            }
            scrollListener = null;
        }
        scrollPosition = -1;
        scrollIndex = -1;
        scrollAttempts = 0;
    }

    void stopScroll() {
        if (binding != null) {
            stopScrollFrame(binding.recyclerView);
        }
    }

    private void showKeyboard() {
        Context context = host.getContext();
        if (context == null || binding == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard() {
        Context context = host.getContext();
        if (context == null || binding == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(binding.searchInput.getWindowToken(), 0);
        }
    }

    void refreshAfterDisplayChange() {
        updateControls();
        TranscriptAdapter adapter = host.getAdapter();
        if (adapter != null && adapter.getSearchHitCount() > 0
                && !getQuery().trim().isEmpty()) {
            jumpToHit(adapter.getCurrentSearchHit());
        }
    }
}
