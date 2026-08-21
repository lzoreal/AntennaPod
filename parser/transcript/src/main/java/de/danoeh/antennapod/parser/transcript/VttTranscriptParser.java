package de.danoeh.antennapod.parser.transcript;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;

public class VttTranscriptParser {
    private static final Pattern TIMESTAMP_PATTERN =
            Pattern.compile("^(?:([0-9]{1,2}):)?([0-9]{2}):([0-9]{2})\\.([0-9]{3})$");

    private static final Pattern VOICE_SPAN =
            Pattern.compile("<v(?:\\.[^\t\n\r &<>.]+)*[ \t]([^\n\r&>]+)>");

    private record Timings(long start, long end) {}

    public static Transcript parse(String str) {
        if (StringUtils.isBlank(str)) {
            return null;
        }

        str = str.replaceAll("\r\n?", "\n");
        List<String> lines = Arrays.asList(str.split("\n"));

        Transcript transcript = new Transcript();
        Iterator<String> iterator = lines.iterator();
        Set<String> speakers = new HashSet<>();
        String speaker = "";

        while (iterator.hasNext()) {
            String line = iterator.next();

            if (!line.contains("-->")) {
                continue;
            }

            Timings timings = parseCueTimings(line);
            if (timings == null) {
                return null;
            }

            List<String> payloadLines = parseCuePayloadLines(iterator);
            if (payloadLines.isEmpty()) {
                continue;
            }

            boolean cueHasMultipleLines = payloadLines.size() > 1;

            StringBuilder cleanedPayload = new StringBuilder();
            for (String payloadLine : payloadLines) {
                Matcher matcher = VOICE_SPAN.matcher(payloadLine);
                if (matcher.find()) {
                    speaker = matcher.group(1);
                    speakers.add(speaker);
                }
                String cleaned = Jsoup.parse(payloadLine).text();
                if (!cleaned.isEmpty()) {
                    if (cleanedPayload.length() > 0) {
                        cleanedPayload.append("\n");
                    }
                    cleanedPayload.append(cleaned);
                }
            }

            if (cueHasMultipleLines) {
                transcript.setBilingual(true);
            }

            TranscriptSegment segment = new TranscriptSegment(
                    timings.start, timings.end, cleanedPayload.toString(), speaker);
            transcript.addSegment(segment);
        }

        if (transcript.getSegmentCount() == 0) {
            return null;
        }
        transcript.setSpeakers(speakers);
        return transcript;
    }

    private static long parseIntOrNull(@Nullable String s) {
        return StringUtils.isEmpty(s) ? 0 : Integer.parseInt(s);
    }

    private static long parseTimestamp(@NonNull String timestamp) {
        Matcher matcher = TIMESTAMP_PATTERN.matcher(timestamp);
        if (!matcher.matches()) {
            return -1;
        }
        long hours = parseIntOrNull(matcher.group(1));
        long minutes = parseIntOrNull(matcher.group(2));
        long seconds = parseIntOrNull(matcher.group(3));
        long milliseconds = parseIntOrNull(matcher.group(4));
        return (hours * 60 * 60 * 1000) + (minutes * 60 * 1000) + (seconds * 1000) + milliseconds;
    }

    @Nullable
    private static Timings parseCueTimings(@NonNull String line) {
        String[] timestamps = line.split("-->");
        if (timestamps.length < 2) {
            return null;
        }
        long start = parseTimestamp(timestamps[0].trim());
        long end = parseTimestamp(timestamps[1].trim().split("[ \t]")[0]);
        if (start == -1 || end == -1) {
            return null;
        }
        return new Timings(start, end);
    }

    @NonNull
    private static List<String> parseCuePayloadLines(@NonNull Iterator<String> iterator) {
        List<String> lines = new ArrayList<>();
        while (iterator.hasNext()) {
            String line = iterator.next();
            if (line.isEmpty()) {
                break;
            }
            lines.add(line.strip());
        }
        return lines;
    }
}
