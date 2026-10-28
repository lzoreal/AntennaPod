package de.danoeh.antennapod.parser.transcript;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.internal.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.feed.TranscriptWord;

public class JsonTranscriptParser {
    private static final int ESTIMATED_CHARS_PER_SECOND = 15;
    private static final long MIN_ESTIMATED_SPAN = 500L;
    private static final long MAX_ESTIMATED_SPAN = 10000L;

    public static Transcript parse(String jsonStr) {
        try {
            Transcript transcript = new Transcript();
            long startTime = -1L;
            long endTime = -1L;
            long segmentStartTime = -1L;
            long segmentEndTime = -1L;
            long duration = 0L;
            String speaker = "";
            String prevSpeaker = "";
            String segmentBody = "";
            JSONArray objSegments;
            Set<String> speakers = new HashSet<>();

            try {
                JSONObject obj = new JSONObject(jsonStr);
                objSegments = obj.getJSONArray("segments");
            } catch (JSONException e) {
                e.printStackTrace();
                return null;
            }

            if (objSegments.length() > 0) {
                JSONObject first = objSegments.getJSONObject(0);
                if (first.has("start") && first.has("text")) {
                    return parseTimedSegments(objSegments);
                }
            }

            for (int i = 0; i < objSegments.length(); i++) {
                JSONObject jsonObject = objSegments.getJSONObject(i);
                long parsedStartTime =
                        Double.valueOf(jsonObject.optDouble("startTime", -1) * 1000L).longValue();
                long parsedEndTime =
                        Double.valueOf(jsonObject.optDouble("endTime", -1) * 1000L).longValue();
                if (parsedStartTime < 0 || parsedEndTime <= parsedStartTime) {
                    continue;
                }
                segmentEndTime = endTime;
                startTime = parsedStartTime;
                endTime = parsedEndTime;
                if (segmentStartTime == -1L) {
                    segmentStartTime = startTime;
                }
                duration += endTime - startTime;

                prevSpeaker = speaker;
                speaker = jsonObject.isNull("speaker") ? "" : jsonObject.optString("speaker");
                if (StringUtils.isEmpty(speaker) && StringUtils.isNotEmpty(prevSpeaker)) {
                    speaker = prevSpeaker;
                }
                if (StringUtils.isNotEmpty(speaker)) {
                    speakers.add(speaker);
                }
                String body = jsonObject.optString("body");
                if (!prevSpeaker.equals(speaker)) {
                    if (StringUtils.isNotEmpty(segmentBody) && segmentEndTime > segmentStartTime) {
                        segmentBody = StringUtils.trim(segmentBody);
                        transcript.addSegment(new TranscriptSegment(segmentStartTime,
                                segmentEndTime,
                                segmentBody,
                                prevSpeaker));
                        segmentStartTime = startTime;
                        segmentBody = body.toString();
                        duration = 0L;
                        continue;
                    }
                }

                segmentBody += " " + body;

                if (duration >= TranscriptParser.MIN_SPAN) {
                    // Look ahead and make sure the next segment does not start with an alphanumeric character
                    if ((i + 1) < objSegments.length()) {
                        String nextBody = objSegments.getJSONObject(i + 1).optString("body");
                        String nextSegmentFirstChar = nextBody.isEmpty() ? "" : nextBody.substring(0, 1);
                        if (!StringUtils.isAlphanumeric(nextSegmentFirstChar)
                                && (duration < TranscriptParser.MAX_SPAN)) {
                            continue;
                        }
                    }
                    segmentBody = StringUtils.trim(segmentBody);
                    transcript.addSegment(new TranscriptSegment(segmentStartTime, endTime, segmentBody, speaker));
                    duration = 0L;
                    segmentBody = "";
                    segmentStartTime = -1L;
                }
            }

            if (!StringUtil.isBlank(segmentBody)) {
                segmentBody = StringUtils.trim(segmentBody);
                transcript.addSegment(new TranscriptSegment(segmentStartTime, endTime, segmentBody, speaker));
            }

            if (transcript.getSegmentCount() > 0) {
                transcript.setSpeakers(speakers);
                return transcript;
            } else {
                return null;
            }

        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static Transcript parseTimedSegments(JSONArray objSegments) throws JSONException {
        Transcript transcript = new Transcript();
        Set<String> speakers = new HashSet<>();
        String speaker = "";
        String prevSpeaker = "";
        for (int i = 0; i < objSegments.length(); i++) {
            JSONObject jsonObject = objSegments.getJSONObject(i);
            long startTime = Math.round(jsonObject.optDouble("start", -1));
            long endTime = Math.round(jsonObject.optDouble("end", -1));
            String body = jsonObject.optString("text");
            if (startTime < 0 || StringUtils.isBlank(body)) {
                continue;
            }
            if (endTime <= startTime) {
                endTime = startTime + estimateSpan(body);
            }
            prevSpeaker = speaker;
            speaker = jsonObject.isNull("speaker") ? "" : jsonObject.optString("speaker");
            if (StringUtils.isEmpty(speaker) && StringUtils.isNotEmpty(prevSpeaker)) {
                speaker = prevSpeaker;
            }
            if (StringUtils.isNotEmpty(speaker)) {
                speakers.add(speaker);
            }
            List<TranscriptWord> wordTimings =
                    parseWordTimings(jsonObject.optJSONArray("words"), body);
            transcript.addSegment(new TranscriptSegment(startTime, endTime, body, speaker,
                    wordTimings));
        }
        if (transcript.getSegmentCount() == 0) {
            return null;
        }
        transcript.setSpeakers(speakers);
        return transcript;
    }

    private static long estimateSpan(String body) {
        long span = Math.round(body.length() * 1000.0 / ESTIMATED_CHARS_PER_SECOND);
        return Math.max(MIN_ESTIMATED_SPAN, Math.min(MAX_ESTIMATED_SPAN, span));
    }

    private static List<TranscriptWord> parseWordTimings(JSONArray words, String text) {
        if (words == null || words.length() == 0) {
            return Collections.emptyList();
        }
        List<TranscriptWord> result = new ArrayList<>();
        int cursor = 0;
        long lastStartTime = -1;
        for (int i = 0; i < words.length(); i++) {
            JSONArray word = words.optJSONArray(i);
            if (word == null || word.length() < 3) {
                continue;
            }
            String token = word.optString(0, "");
            long startTime = Math.round(word.optDouble(1, -1));
            long duration = Math.round(word.optDouble(2, -1));
            if (token.isEmpty() || startTime < 0 || duration < 0) {
                continue;
            }
            int startChar = locateToken(text, token, cursor);
            if (startChar < 0) {
                continue;
            }
            cursor = startChar + token.length();
            if (startTime < lastStartTime) {
                continue;
            }
            lastStartTime = startTime;
            result.add(new TranscriptWord(startChar, cursor, startTime, startTime + duration));
        }
        return result;
    }

    private static int locateToken(String text, String token, int from) {
        int index = from;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        int match = matchAt(text, token, index);
        if (match >= 0) {
            return match;
        }
        int limit = Math.min(text.length() - token.length(), index + token.length() + 40);
        for (int i = index; i <= limit; i++) {
            if (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) {
                continue;
            }
            match = matchAt(text, token, i);
            if (match >= 0) {
                return match;
            }
        }
        return -1;
    }

    private static int matchAt(String text, String token, int index) {
        if (index + token.length() > text.length()) {
            return -1;
        }
        if (text.regionMatches(index, token, 0, token.length())
                || text.regionMatches(true, index, token, 0, token.length())) {
            return index;
        }
        return -1;
    }
}
