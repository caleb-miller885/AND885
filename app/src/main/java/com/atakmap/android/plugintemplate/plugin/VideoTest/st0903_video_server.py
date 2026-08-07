import json
import math
import os
import subprocess
import sys
import threading
import time

import gi

gi.require_version("Gst", "1.0")
from gi.repository import Gst, GLib

from st0903_functions import build_packet, build_vmti_local_set, encode_vtarget

ADELAIDE_LAT = -34.9285
ADELAIDE_LON = 138.6007
FOOTPRINT_HALF_DEG = 0.01  # fake aperture square, not a real sensor footprint

HTTP_PORT = 8091
HOST = ""

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
VIDEO_PATH = sys.argv[1] if len(sys.argv) > 1 else os.path.join(SCRIPT_DIR, "sample_video.mp4")
DETECTIONS_PATH = sys.argv[2] if len(sys.argv) > 2 else os.path.join(SCRIPT_DIR, "yolo_records.json")

DETECTION_FPS = 12.0  # frame rate the detections.json based on video fps
KLV_PUSH_INTERVAL_S = 1.0 / DETECTION_FPS  # one push per analyzed frame

with open(DETECTIONS_PATH) as f:
    RECORDS = json.load(f)
RECORDS_DURATION_S = len(RECORDS) / DETECTION_FPS

ONTOLOGY_IRI = "https://example.org/cuas-demo-ontology"


def build_vmti_field(t):
    frame_idx = int((t % RECORDS_DURATION_S) * DETECTION_FPS)
    record = RECORDS[frame_idx]
    frame_width, frame_height = record["frame_width"], record["frame_height"]

    vtargets = []
    ontologies = []
    for i, tgt in enumerate(record["targets"]):
        row_tl, col_tl, row_br, col_br = tgt["bbox"]
        centroid_row = (row_tl + row_br) // 2
        centroid_col = (col_tl + col_br) // 2

        label = tgt.get("label")
        ontology_id = i + 1 if label else None
        if label:
            ontologies.append((ontology_id, ONTOLOGY_IRI, f"{ONTOLOGY_IRI}#{label}", label))

        vtargets.append(encode_vtarget(
            tgt["id"], centroid_row, centroid_col,
            row_tl, col_tl, row_br, col_br,
            frame_width,
            priority=tgt.get("priority"),
            confidence=tgt.get("confidence"),
            ontology_id=ontology_id))

    print(f"[klv] t={t:.1f}s frame_idx={frame_idx} targets={len(vtargets)} "
          f"labels={[t.get('label') for t in record['targets']]}")
    return build_vmti_local_set(frame_width, frame_height, vtargets, ontologies)


def build_klv_packet(t):
    lon_scale = math.cos(math.radians(ADELAIDE_LAT))
    corner_offsets = [
        (FOOTPRINT_HALF_DEG, -FOOTPRINT_HALF_DEG / lon_scale),
        (FOOTPRINT_HALF_DEG, FOOTPRINT_HALF_DEG / lon_scale),
        (-FOOTPRINT_HALF_DEG, FOOTPRINT_HALF_DEG / lon_scale),
        (-FOOTPRINT_HALF_DEG, -FOOTPRINT_HALF_DEG / lon_scale),
    ]
    return build_packet(build_vmti_field(t), ADELAIDE_LAT, ADELAIDE_LON, corner_offsets)


KLV_PIPELINE = (
    f'filesrc location="{VIDEO_PATH}" ! decodebin ! videoconvert '
    "! video/x-raw,format=I420 ! identity sync=true "
    "! x264enc tune=zerolatency speed-preset=ultrafast key-int-max=12 bitrate=2000 "
    "! video/x-h264,profile=constrained-baseline "
    "! h264parse config-interval=1 "
    "! video/x-h264,stream-format=byte-stream,alignment=au "
    "! queue ! mux. "
    "appsrc name=klvsrc format=time is-live=true block=true "
    "caps=meta/x-klv,parsed=(boolean)true "
    "! queue ! mux. "
    "mpegtsmux name=mux alignment=7 "
    "! appsink name=tssink emit-signals=true sync=false"
)


def start_klv_http_server():
    state = {"proc": None, "pipeline": None, "timeout_id": None}

    def start_connection():
        proc = subprocess.Popen(
            ["ffmpeg", "-f", "mpegts", "-i", "pipe:0",
             "-map", "0", "-c", "copy",
             "-f", "mpegts", "-listen", "1",
             f"http://0.0.0.0:{HTTP_PORT}/klv.ts"],
            stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
        )
        state["proc"] = proc

        def log_stderr():
            for line in proc.stderr:
                print("[ffmpeg klv]", line.decode(errors="replace").rstrip())

        threading.Thread(target=log_stderr, daemon=True).start()
        print(f"[klv] fresh encode + ffmpeg started, waiting for a client on "
              f"http://0.0.0.0:{HTTP_PORT}/klv.ts")

        pipeline = Gst.parse_launch(KLV_PIPELINE)
        appsink = pipeline.get_by_name("tssink")
        klvsrc = pipeline.get_by_name("klvsrc")
        state["pipeline"] = pipeline

        start_time = time.monotonic()

        def push_klv():
            if state["pipeline"] is not pipeline:
                return False
            t = time.monotonic() - start_time
            packet = build_klv_packet(t)
            buf = Gst.Buffer.new_wrapped(packet)
            buf.pts = int(t * Gst.SECOND)
            klvsrc.emit("push-buffer", buf)
            return True

        def on_new_sample(sink):
            sample = sink.emit("pull-sample")
            buf = sample.get_buffer()
            ok, mapinfo = buf.map(Gst.MapFlags.READ)
            if ok:
                try:
                    proc.stdin.write(mapinfo.data)
                    proc.stdin.flush()
                except (BrokenPipeError, ValueError):
                    print("[klv] client disconnected -- exiting so the "
                          "wrapper script can restart the server")
                    proc.kill()
                    os._exit(0)
                buf.unmap(mapinfo)
            return Gst.FlowReturn.OK

        appsink.connect("new-sample", on_new_sample)

        def on_bus_message(bus, message):
            if message.type == Gst.MessageType.ERROR:
                err, debug = message.parse_error()
                print(f"[klv] pipeline error: {err} {debug}")

        bus = pipeline.get_bus()
        bus.add_signal_watch()
        bus.connect("message", on_bus_message)

        pipeline.set_state(Gst.State.PLAYING)
        push_klv()
        state["timeout_id"] = GLib.timeout_add(
            int(KLV_PUSH_INTERVAL_S * 1000), push_klv)

    start_connection()
    return state


def main():
    Gst.init(None)

    klv_state = start_klv_http_server()

    print(f"Replaying {VIDEO_PATH} with detections from {DETECTIONS_PATH}.")
    print(f"Serving http://{HOST}:{HTTP_PORT}/klv.ts")
    print("Ctrl+C to stop.")

    loop = GLib.MainLoop()
    try:
        loop.run()
    finally:
        if klv_state["pipeline"] is not None:
            klv_state["pipeline"].set_state(Gst.State.NULL)
        if klv_state["proc"] is not None:
            klv_state["proc"].terminate()


if __name__ == "__main__":
    main()
