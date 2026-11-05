package de.danoeh.antennapod.ui.screen.playback;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Appending an ASR segment must not move the rows that are already on screen. RecyclerView's
 * predictive layout re-anchors a list whose last row carries an item-decoration footer, which
 * shifted the transcript by one footer gap on every new segment.
 */
@RunWith(RobolectricTestRunner.class)
public class TranscriptInsertStabilityTest {
    private static final int WIDTH = 1220;
    private static final int HEIGHT = 1540;
    private static final int FOOTER = 1018;

    @Test
    public void appendKeepsVisibleRowsInPlace() {
        Context context = RuntimeEnvironment.getApplication();
        TranscriptLikeAdapter adapter = new TranscriptLikeAdapter(new int[]{898, 139, 760, 903});
        RecyclerView recyclerView = new RecyclerView(context);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setAdapter(adapter);
        recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                       @NonNull RecyclerView parent,
                                       @NonNull RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(view);
                if (position == adapter.getItemCount() - 1) {
                    outRect.bottom = FOOTER;
                }
            }
        });
        recyclerView.setItemAnimator(null);
        layout(recyclerView);
        recyclerView.scrollToPosition(89);
        layout(recyclerView);
        recyclerView.scrollBy(0, 200);

        int top88 = topOf(recyclerView, 88);
        int top89 = topOf(recyclerView, 89);

        adapter.appendRow();
        recyclerView.invalidateItemDecorations();
        layout(recyclerView);

        assertEquals(top88, topOf(recyclerView, 88));
        assertEquals(top89, topOf(recyclerView, 89));
        assertEquals(0f, translationOf(recyclerView, 89), 0f);
    }

    private static void layout(RecyclerView recyclerView) {
        recyclerView.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        recyclerView.layout(0, 0, WIDTH, HEIGHT);
    }

    private static int topOf(RecyclerView recyclerView, int position) {
        RecyclerView.ViewHolder holder =
                recyclerView.findViewHolderForAdapterPosition(position);
        return holder == null ? Integer.MIN_VALUE : holder.itemView.getTop();
    }

    private static float translationOf(RecyclerView recyclerView, int position) {
        RecyclerView.ViewHolder holder =
                recyclerView.findViewHolderForAdapterPosition(position);
        return holder == null ? Float.MIN_VALUE : holder.itemView.getTranslationY();
    }

    private static final class TranscriptLikeAdapter
            extends RecyclerView.Adapter<TranscriptLikeAdapter.Holder> {
        private final int[] heights;
        private int count = 90;

        TranscriptLikeAdapter(int[] heights) {
            this.heights = heights;
            setHasStableIds(true);
        }

        void appendRow() {
            count++;
            notifyItemInserted(count - 1);
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = new View(parent.getContext());
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 100));
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            ViewGroup.LayoutParams params = holder.itemView.getLayoutParams();
            params.height = heights[position % heights.length];
            holder.itemView.setLayoutParams(params);
        }

        @Override
        public long getItemId(int position) {
            return position * 1000L;
        }

        @Override
        public int getItemCount() {
            return count;
        }

        static final class Holder extends RecyclerView.ViewHolder {
            Holder(View itemView) {
                super(itemView);
            }
        }
    }
}
