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

    private static final int SCRIPT_UNKNOWN = -1;
    private static final int SCRIPT_LATIN = 0;
    private static final int SCRIPT_CJK = 1;
    private static final int SCRIPT_OTHER = 2;

    private record Timings(long start, long end) {}

    private record Cue(long start, long end, List<String> lines, String speaker) {}

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
        List<Cue> cues = new ArrayList<>();

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

            List<String> cleanedLines = new ArrayList<>();
            for (String payloadLine : payloadLines) {
                Matcher matcher = VOICE_SPAN.matcher(payloadLine);
                if (matcher.find()) {
                    speaker = matcher.group(1);
                    speakers.add(speaker);
                }
                String cleaned = payloadLine.indexOf('<') < 0 && payloadLine.indexOf('&') < 0
                        ? payloadLine
                        : Jsoup.parse(payloadLine).text();
                if (!cleaned.isEmpty()) {
                    cleanedLines.add(cleaned);
                }
            }
            if (!cleanedLines.isEmpty()) {
                cues.add(new Cue(timings.start, timings.end, cleanedLines, speaker));
            }
        }

        int bilingualCues = 0;
        for (Cue cue : cues) {
            if (cue.lines().size() > 1 && hasDifferentScripts(cue.lines())) {
                bilingualCues++;
            }
        }
        boolean bilingual = bilingualCues * 2 > cues.size();

        TranscriptSegment segment = null;
        for (Cue cue : cues) {
            String payload = String.join(bilingual ? "\n" : " ", cue.lines());
            if (!bilingual && segment != null && segment.getSpeaker().equals(cue.speaker())
                    && cue.end() - segment.getStartTime() < TranscriptParser.MAX_SPAN) {
                segment.append(cue.end(), payload);
            } else {
                if (segment != null) {
                    transcript.addSegment(segment);
                }
                segment = new TranscriptSegment(cue.start(), cue.end(), payload, cue.speaker());
                if (bilingual && hasDifferentScripts(cue.lines())) {
                    segment.setTranslationLine(1);
                }
            }
            if (segment.getEndTime() - segment.getStartTime() >= TranscriptParser.MIN_SPAN) {
                transcript.addSegment(segment);
                segment = null;
            }
        }

        if (segment != null) {
            transcript.addSegment(segment);
        }

        if (transcript.getSegmentCount() == 0) {
            return null;
        }
        transcript.setBilingual(bilingual);
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

    private static boolean hasDifferentScripts(@NonNull List<String> lines) {
        int firstScript = -1;
        for (String line : lines) {
            int script = scriptOf(line);
            if (script == SCRIPT_UNKNOWN) {
                continue;
            }
            if (firstScript == -1) {
                firstScript = script;
            } else if (firstScript != script) {
                return true;
            }
        }
        return false;
    }

    private static int scriptOf(@NonNull String line) {
        int latin = 0;
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!Character.isLetter(c)) {
                continue;
            }
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN) {
                latin++;
            } else if ((c >= '\u3040' && c <= '\u9fff') || (c >= '\uac00' && c <= '\ud7af')) {
                cjk++;
            } else {
                other++;
            }
        }
        if (latin == 0 && cjk == 0 && other == 0) {
            return SCRIPT_UNKNOWN;
        }
        if (cjk > latin && cjk >= other) {
            return SCRIPT_CJK;
        }
        if (latin > cjk && latin >= other) {
            return SCRIPT_LATIN;
        }
        return SCRIPT_OTHER;
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
