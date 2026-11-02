#!/usr/bin/env bash
# Generates a local feed with two episodes that share a 20 s "ad" at different positions.
set -euo pipefail
OUT="${1:?output dir}"
mkdir -p "$OUT"

ffmpeg -y -hide_banner -loglevel error -f lavfi \
  -i "anoisesrc=color=pink:duration=20:sample_rate=44100" \
  -af "volume=0.4,tremolo=f=3:d=0.8" -ac 1 "$OUT/ad.mp3"

for pair in "a:30" "b:150"; do
  IFS=: read -r name at <<< "$pair"
  ffmpeg -y -hide_banner -loglevel error -f lavfi \
    -i "anoisesrc=color=white:duration=240:sample_rate=44100" \
    -i "$OUT/ad.mp3" \
    -filter_complex "[0:a]volume=0.15,atrim=0:$at,asetpts=PTS-STARTPTS[x];\
[0:a]volume=0.15,atrim=$at:240,asetpts=PTS-STARTPTS[y];\
[1:a]asplit=1[ad];[x][ad][y]concat=n=3:v=0:a=1[o]" \
    -map "[o]" -b:a 128k "$OUT/ep_$name.mp3"
done

python - "$OUT" <<'PY'
import os, sys
out = sys.argv[1]
items = []
for name, title, hour in (("a", "Episode A", "01"), ("b", "Episode B", "02")):
    size = os.path.getsize(os.path.join(out, f"ep_{name}.mp3"))
    items.append(
        f"<item><title>{title}</title><guid>ad-test-{name}</guid>"
        f"<pubDate>Mon, 26 Oct 2026 {hour}:00:00 GMT</pubDate>"
        f"<enclosure url=\"http://127.0.0.1:8123/ep_{name}.mp3\" length=\"{size}\" type=\"audio/mpeg\"/>"
        f"</item>")
xml = ('<?xml version="1.0"?><rss version="2.0"><channel>'
       '<title>Ad Test Feed</title><link>http://127.0.0.1:8123/</link>'
       '<description>Local ad-detection fixture</description>'
       + "".join(items) + '</channel></rss>')
with open(os.path.join(out, "feed.xml"), "w", encoding="utf-8") as f:
    f.write(xml)
PY

ls -l "$OUT"
