package de.danoeh.antennapod.parser.transcript;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.internal.StringUtil;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;

public class SrtTranscriptParser {
    private static final Pattern TIMECODE_PATTERN = Pattern.compile("^([0-9]{2}):([0-9]{2}):([0-9]{2}),([0-9]{3})$");

    public static Transcript parse(String str) {
        if (StringUtils.isBlank(str)) {
            return null;
        }
        str = str.replaceAll("\r\n", "\n");

        Transcript transcript = new Transcript();
        List<String> lines = Arrays.asList(str.split("\n"));
        Set<String> speakerLabels = findSpeakerLabels(lines);
        Iterator<String> iter = lines.iterator();
        String speaker = "";
        String prevSpeaker = "";
        StringBuilder body;
        String line;
        String segmentBody = "";
        long startTimecode = -1L;
        long spanStartTimecode = -1L;
        long spanEndTimecode = -1L;
        long endTimecode = -1L;
        long duration = 0L;
        Set<String> speakers = new HashSet<>();

        while (iter.hasNext()) {
            body = new StringBuilder();
            String firstPayloadLine = null;
            line = iter.next();

            if (line.isEmpty()) {
                continue;
            }

            if (line.contains("-->")) {
                String[] timecodes = line.split("-->");
                if (timecodes.length < 2) {
                    continue;
                }
                long parsedStartTimecode = parseTimecode(timecodes[0].trim());
                long parsedEndTimecode = parseTimecode(timecodes[1].trim());
                if (parsedStartTimecode == -1 || parsedEndTimecode == -1) {
                    continue;
                }
                spanEndTimecode = endTimecode;
                startTimecode = parsedStartTimecode;
                endTimecode = parsedEndTimecode;

                if (spanStartTimecode == -1) {
                    spanStartTimecode = startTimecode;
                }
                duration += endTimecode - startTimecode;
                do {
                    if (!iter.hasNext()) {
                        break;
                    }
                    line = iter.next();
                    if (StringUtil.isBlank(line)) {
                        break;
                    }
                    String strippedLine = line.strip();
                    if (firstPayloadLine == null) {
                        firstPayloadLine = strippedLine;
                    }
                    body.append(strippedLine);
                    body.append(" ");
                } while (iter.hasNext());
            }

            int colon = -1;
            int speakerBodyStart = -1;
            String candidateSpeaker = "";
            if (firstPayloadLine != null) {
                int inlineColon = firstPayloadLine.indexOf(": ");
                if (inlineColon > 0) {
                    colon = inlineColon;
                    speakerBodyStart = inlineColon + 2;
                    candidateSpeaker = firstPayloadLine.substring(0, inlineColon).strip();
                } else if (firstPayloadLine.endsWith(":") && firstPayloadLine.length() > 1) {
                    colon = firstPayloadLine.length() - 1;
                    speakerBodyStart = firstPayloadLine.length();
                    candidateSpeaker = firstPayloadLine.substring(0, colon).strip();
                }
            }
            if (colon > 0 && speakerLabels.contains(candidateSpeaker)) {
                prevSpeaker = speaker;
                speaker = candidateSpeaker;
                speakers.add(speaker);
                String firstLineBody = firstPayloadLine.substring(speakerBodyStart).strip();
                String remainingBody = body.substring(firstPayloadLine.length()).strip();
                body = new StringBuilder((firstLineBody + " " + remainingBody).strip());
                if (StringUtils.isNotEmpty(prevSpeaker) && !StringUtils.equals(speaker, prevSpeaker)) {
                    if (StringUtils.isNotEmpty(segmentBody) && spanEndTimecode > spanStartTimecode) {
                        transcript.addSegment(new TranscriptSegment(spanStartTimecode,
                                spanEndTimecode, segmentBody, prevSpeaker));
                        duration = 0L;
                        spanStartTimecode = startTimecode;
                        segmentBody = body.toString();
                        continue;
                    }
                }
            } else {
                if (StringUtils.isNotEmpty(prevSpeaker) && StringUtils.isEmpty(speaker)) {
                    speaker = prevSpeaker;
                }
            }

            segmentBody += " " + body;
            segmentBody = StringUtils.trim(segmentBody);
            if (duration >= TranscriptParser.MIN_SPAN && endTimecode > spanStartTimecode) {
                transcript.addSegment(new TranscriptSegment(spanStartTimecode, endTimecode, segmentBody, speaker));
                duration = 0L;
                spanStartTimecode = -1L;
                segmentBody = "";
            }
        }

        if (!StringUtil.isBlank(segmentBody) && endTimecode > spanStartTimecode) {
            segmentBody = StringUtils.trim(segmentBody);
            transcript.addSegment(new TranscriptSegment(spanStartTimecode, endTimecode, segmentBody, speaker));
        }
        if (transcript.getSegmentCount() > 0) {
            transcript.setSpeakers(speakers);
            return transcript;
        } else {
            return null;
        }
    }

    static long parseTimecode(String timecode) {
        Matcher matcher = TIMECODE_PATTERN.matcher(timecode);
        if (!matcher.matches()) {
            return -1;
        }
        long hours = Integer.parseInt(matcher.group(1));
        long minutes = Integer.parseInt(matcher.group(2));
        long seconds = Integer.parseInt(matcher.group(3));
        long milliseconds = Integer.parseInt(matcher.group(4));
        return (hours * 60 * 60 * 1000) + (minutes * 60 * 1000) + (seconds * 1000) + milliseconds;
    }

    private static Set<String> findSpeakerLabels(List<String> lines) {
        Map<String, Integer> counts = new HashMap<>();
        for (String line : lines) {
            int colon = line.indexOf(": ");
            String candidate = null;
            if (colon > 0) {
                candidate = line.substring(0, colon).strip();
            } else if (line.endsWith(":") && line.length() > 1) {
                candidate = line.substring(0, line.length() - 1).strip();
            }
            if (candidate != null && isValidSpeaker(candidate)) {
                counts.merge(candidate, 1, Integer::sum);
            }
        }
        Set<String> labels = new HashSet<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            // A label is only a speaker if it repeats or is the only candidate (single-speaker file)
            if (counts.size() == 1 || entry.getValue() >= 2) {
                labels.add(entry.getKey());
            }
        }
        return labels;
    }

    private static boolean isValidSpeaker(String name) {
        if (name.isEmpty() || name.length() > 40) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isLetter(name.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
