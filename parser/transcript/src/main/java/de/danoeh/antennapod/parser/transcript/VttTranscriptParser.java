package de.danoeh.antennapod.parser.transcript;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
                continue;
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
        cues.sort(Comparator.comparingLong(Cue::start));
        for (Cue cue : cues) {
            if (cue.lines().size() > 1 && hasDifferentScripts(cue.lines())) {
                bilingualCues++;
            }
        }
        int alternatingPrimaryScript = bilingualCues == 0
                ? detectAlternatingPrimaryScript(cues) : TranscriptSentenceUtils.SCRIPT_UNKNOWN;
        boolean alternatingBilingual =
                alternatingPrimaryScript != TranscriptSentenceUtils.SCRIPT_UNKNOWN;
        boolean bilingual = bilingualCues * 2 > cues.size() || alternatingBilingual;

        if (alternatingBilingual) {
            addAlternatingSegments(transcript, cues, alternatingPrimaryScript);
        } else {
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
                }
                if (segment.getEndTime() - segment.getStartTime() >= TranscriptParser.MIN_SPAN) {
                    transcript.addSegment(segment);
                    segment = null;
                }
            }

            if (segment != null) {
                transcript.addSegment(segment);
            }
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
        if (start == -1 || end == -1 || end <= start) {
            return null;
        }
        return new Timings(start, end);
    }

    private static int detectAlternatingPrimaryScript(@NonNull List<Cue> cues) {
        if (cues.size() < 4) {
            return TranscriptSentenceUtils.SCRIPT_UNKNOWN;
        }
        int primaryScript = TranscriptSentenceUtils.SCRIPT_UNKNOWN;
        int otherScript = TranscriptSentenceUtils.SCRIPT_UNKNOWN;
        int previousScript = TranscriptSentenceUtils.SCRIPT_UNKNOWN;
        int primaryCount = 0;
        int otherCount = 0;
        for (Cue cue : cues) {
            int script = TranscriptSentenceUtils.scriptOf(String.join(" ", cue.lines()));
            if (script == TranscriptSentenceUtils.SCRIPT_UNKNOWN) {
                return TranscriptSentenceUtils.SCRIPT_UNKNOWN;
            }
            if (primaryScript == TranscriptSentenceUtils.SCRIPT_UNKNOWN) {
                primaryScript = script;
            }
            if (script == primaryScript) {
                primaryCount++;
            } else {
                if (otherScript == TranscriptSentenceUtils.SCRIPT_UNKNOWN) {
                    otherScript = script;
                } else if (script != otherScript) {
                    return TranscriptSentenceUtils.SCRIPT_UNKNOWN;
                }
                otherCount++;
            }
            if (previousScript != TranscriptSentenceUtils.SCRIPT_UNKNOWN
                    && script == previousScript) {
                return TranscriptSentenceUtils.SCRIPT_UNKNOWN;
            }
            previousScript = script;
        }
        if (primaryCount < 2 || otherCount < 2) {
            return TranscriptSentenceUtils.SCRIPT_UNKNOWN;
        }
        return primaryScript;
    }

    private static void addAlternatingSegments(Transcript transcript, List<Cue> cues,
                                               int primaryScript) {
        int index = 0;
        while (index < cues.size()) {
            Cue primaryCue = cues.get(index);
            if (TranscriptSentenceUtils.scriptOf(String.join(" ", primaryCue.lines()))
                    != primaryScript) {
                transcript.addSegment(new TranscriptSegment(primaryCue.start(), primaryCue.end(),
                        String.join(" ", primaryCue.lines()), primaryCue.speaker()));
                index++;
                continue;
            }
            Cue translationCue = null;
            if (index + 1 < cues.size()
                    && TranscriptSentenceUtils.scriptOf(String.join(" ", cues.get(index + 1).lines()))
                    != primaryScript) {
                translationCue = cues.get(index + 1);
                index += 2;
            } else {
                index++;
            }
            if (translationCue != null) {
                transcript.addSegment(new TranscriptSegment(primaryCue.start(),
                        Math.max(primaryCue.end(), translationCue.end()),
                        String.join(" ", primaryCue.lines()) + "\n"
                                + String.join(" ", translationCue.lines()),
                        primaryCue.speaker()));
            } else {
                transcript.addSegment(new TranscriptSegment(primaryCue.start(), primaryCue.end(),
                        String.join(" ", primaryCue.lines()), primaryCue.speaker()));
            }
        }
    }

    private static boolean hasDifferentScripts(@NonNull List<String> lines) {
        int firstScript = TranscriptSentenceUtils.SCRIPT_UNKNOWN;
        for (String line : lines) {
            int script = TranscriptSentenceUtils.scriptOf(line);
            if (script == TranscriptSentenceUtils.SCRIPT_UNKNOWN) {
                continue;
            }
            if (firstScript == TranscriptSentenceUtils.SCRIPT_UNKNOWN) {
                firstScript = script;
            } else if (firstScript != script) {
                return true;
            }
        }
        return false;
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
