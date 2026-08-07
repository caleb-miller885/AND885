# From an MP4 to real ST0903 KLV, using `json_to_st0903.py`

Takes any raw MP4 (or other container ffmpeg/GStreamer can read) with
per-frame object detections and produces an MPEG-TS carrying the *same*
video plus real MISB ST0601(+nested ST0903.6 VMTI) KLV metadata -- ready to
hand to your own hosting/serving setup.

`json_to_st0903.py` (this directory) does the actual JSON→KLV encoding. It's
self-contained (stdlib + GStreamer only) and only accepts one specific input
shape: an MPEG-TS with **exactly one video stream and one metadata stream**,
where the metadata stream carries JSON text in place of real KLV. So there
are two steps before it can run: get detections into that JSON shape, then
mux video + JSON into that intermediate TS.

## 1. Get per-frame detections into the expected JSON shape

Run whatever detection pipeline you have -- a model, a hosted inference API,
ground-truth labels, another team's sensor output, anything -- and produce
one JSON object per video frame, in frame order:

```json
[
  {
    "frame_width": 768, "frame_height": 432,
    "targets": [
      {"id": 1, "bbox": [row_tl, col_tl, row_br, col_br],
       "label": "person", "confidence": 82},
      ...
    ]
  },
  ...
]
```

- `frame_width`/`frame_height` must match the actual video's pixel
  dimensions (used to compute ST0903's pixel-number encoding for each box).
- `bbox` is `[row_tl, col_tl, row_br, col_br]` -- top-left and bottom-right
  corners, row (y) before column (x), 0-based pixel coordinates.
- `id` must be a *stable* identifier for the same physical object across
  consecutive frames (this becomes ST0903's `targetId`) -- needs a tracker,
  not just per-frame detection, or every box gets treated as a brand-new
  target every frame.
- `label`/`confidence` (0-100) are optional per target; omit either and
  that field is simply not sent for that target.
- A frame with no detections is just `"targets": []` -- not an error.

`run_yolo.py` in this directory is one working example of this step
(YOLOv8 + ByteTrack, via a separate Python 3.12 `uv` venv since this
project's system Python has no pip and no wheels for its own version) --
swap it for whatever your actual pipeline is.

## 2. Mux the video + JSON into the intermediate TS

`json_to_st0903.py` needs a TS where the JSON sits on a metadata track
alongside the video, formatted the same way a real KLV track would be
(PES-wrapped, one JSON record per PES packet, one video + one other
stream). Build it with GStreamer: demux/passthrough the source video,
push each JSON record as its own `appsrc` buffer timestamped to that
frame's position, mux both into one file.

```python
import json, sys
import gi
gi.require_version("Gst", "1.0")
from gi.repository import Gst, GLib

Gst.init(None)
FPS = 12.0  # match your video's actual frame rate

with open("detections.json") as f:
    records = json.load(f)

pipeline = Gst.parse_launch(
    "filesrc location=input.mp4 ! qtdemux name=demux "
    "demux. ! queue ! h264parse config-interval=-1 "
    "! video/x-h264,stream-format=byte-stream,alignment=au ! mux. "
    "appsrc name=klvsrc format=time is-live=false do-timestamp=false "
    "caps=meta/x-klv,parsed=(boolean)true ! queue ! mux. "
    "mpegtsmux name=mux alignment=7 ! filesink location=intermediate.ts"
)
appsrc = pipeline.get_by_name("klvsrc")
loop = GLib.MainLoop()
bus = pipeline.get_bus()
bus.add_signal_watch()
bus.connect("message", lambda b, m: loop.quit()
            if m.type in (Gst.MessageType.EOS, Gst.MessageType.ERROR) else None)
pipeline.set_state(Gst.State.PLAYING)

for i, rec in enumerate(records):
    buf = Gst.Buffer.new_wrapped(json.dumps(rec).encode("utf-8"))
    buf.pts = round(i / FPS * Gst.SECOND)
    appsrc.emit("push-buffer", buf)
appsrc.emit("end-of-stream")

loop.run()
pipeline.set_state(Gst.State.NULL)
```

If the source video's keyframe interval is more than ~1 second, re-encode it
first with a short GOP (`ffmpeg -i input.mp4 -c:v libx264 -profile:v baseline
-g 12 -keyint_min 12 -sc_threshold 0 -bf 0 short_gop.mp4`, `-g`/`-keyint_min`
= roughly your video's own fps so keyframes land ~1/sec) -- ATAK's connection
has a short read timeout, and a client that has to wait several seconds for
the next keyframe after connecting can lose that race. Use `short_gop.mp4`
as the `filesrc location=` input above instead of the original.

## 3. Convert to real ST0903 KLV

```
python3 json_to_st0903.py intermediate.ts output.ts
```

No flags needed -- it auto-detects the metadata stream (whichever PMT entry
isn't video) since there's guaranteed to be exactly one of each. `output.ts`
has the video passed through byte-for-byte untouched and real MISB
ST0601/ST0903.6 KLV in place of the JSON -- real UAS Datalink LS universal
key, real `vTargetSeries`/VMask-encoded boxes, `targetConfidenceLevel`/
`targetPriority` tags, and object labels resolved through the spec's actual
`ontologySeries`→`vObjectSeries` mechanism. Hand this file to your hosting
setup.

## One gotcha worth knowing before you wire up hosting

A pre-built static `.ts` file served via a plain `ffmpeg -i output.ts -c
copy -f mpegts -listen 1 ...` was found, after a full day of on-device
isolation testing, to reliably get the video playing on ATAK but *never*
deliver a single decoded KLV/VMTI unit to the app (`metadataChanged()`
never fires) -- despite the KLV in the file being independently verified
byte-correct. Root cause was never fully pinned down. What *is* confirmed
reliable: serving through a live-style pipeline instead -- GStreamer
pipeline ending in `appsink`, with each buffer manually written into a
persistent `ffmpeg -f mpegts -i pipe:0 -c copy -f mpegts -listen 1 ...`
subprocess's stdin, rather than pointing ffmpeg at the finished file
directly. See `file_klv_server_test.py` in this directory for a full
working example of that pattern.
