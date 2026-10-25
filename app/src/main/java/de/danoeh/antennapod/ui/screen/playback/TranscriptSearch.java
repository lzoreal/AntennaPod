package de.danoeh.antennapod.ui.screen.playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class TranscriptSearch {
    private TranscriptSearch() {
    }

    static List<Match> findAll(String text, String query) {
        if (text == null || query == null) {
            return Collections.emptyList();
        }
        String needle = query.trim();
        if (needle.isEmpty() || text.length() < needle.length()) {
            return Collections.emptyList();
        }
        List<Match> matches = new ArrayList<>();
        int index = 0;
        while (index <= text.length() - needle.length()) {
            if (text.regionMatches(true, index, needle, 0, needle.length())) {
                matches.add(new Match(index, index + needle.length()));
                index += needle.length();
            } else {
                index++;
            }
        }
        return matches;
    }

    static final class Match {
        final int start;
        final int end;

        Match(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
