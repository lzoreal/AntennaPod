package de.danoeh.antennapod.model.feed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

public class Transcript {
    private Set<String> speakers = Collections.emptySet();
    private final ArrayList<TranscriptSegment> segments = new ArrayList<>();
    private boolean bilingual = false;

    public void addSegment(TranscriptSegment segment) {
        int low = 0;
        int high = segments.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (segments.get(middle).getStartTime() <= segment.getStartTime()) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        segments.add(low, segment);
    }

    public int findSegmentIndexBefore(long time) {
        int a = 0;
        int b = segments.size() - 1;
        while (a < b) {
            int pivot = (a + b + 1) / 2;
            if (segments.get(pivot).getStartTime() > time) {
                b = pivot - 1;
            } else {
                a = pivot;
            }
        }
        return a;
    }

    public TranscriptSegment getSegmentAt(int index) {
        return segments.get(index);
    }

    public TranscriptSegment getSegmentAtTime(long time) {
        return getSegmentAt(findSegmentIndexBefore(time));
    }

    public Set<String> getSpeakers() {
        return speakers;
    }

    public void setSpeakers(Set<String> speakers) {
        this.speakers = speakers;
    }

    public int getSegmentCount() {
        return segments.size();
    }

    public boolean isBilingual() {
        return bilingual;
    }

    public void setBilingual(boolean bilingual) {
        this.bilingual = bilingual;
    }
}
