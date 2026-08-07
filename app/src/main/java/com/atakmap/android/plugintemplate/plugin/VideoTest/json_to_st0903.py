#!/usr/bin/env python3

# Converts an MPEG-TS with exactly one video stream and one metadata stream
# into a new MPEG-TS carrying real MISB ST0601(+nested ST0903 VMTI) KLV
# instead of the metadata stream's custom JSON, video untouched.

# Usage: python3 json_to_st0903.py input.ts output.ts


import json
import struct
import sys

import gi

gi.require_version("Gst", "1.0")
from gi.repository import Gst, GLib  # noqa: E402

TS_PACKET_SIZE = 188
SYNC_BYTE = 0x47
PTS_HZ = 90000
NS_PER_SEC = 1_000_000_000


# KLV encoding: ST0601 UAS Datalink LS + nested ST0903.6 VMTI LS

UAS_LS_KEY = bytes.fromhex("060E2B34020B01010E01030101000000")


def ber_length(n: int) -> bytes:
    # short form if n <= 127, long form otherwise
    if n <= 127:
        return bytes([n])
    out = []
    while n > 0:
        out.insert(0, n & 0xFF)
        n >>= 8
    return bytes([0x80 | len(out)]) + bytes(out)


def tlv(tag: int, value: bytes) -> bytes:
    assert 0 < tag < 128, "only single-byte local tags are used here"
    return bytes([tag]) + ber_length(len(value)) + value


def _scale_int(value, vmin, vmax, nbytes):
    # fixed-length signed field scaling, only used for frame_center_lat/lon -
    # unrelated to ST0903, kept for the optional passthrough in build_packet()
    maxi = (1 << (8 * nbytes - 1)) - 1
    frac = value / max(abs(vmin), abs(vmax))
    frac = min(max(frac, -1.0), 1.0)
    raw = round(frac * maxi)
    return raw.to_bytes(nbytes, "big", signed=True)


def ber_oid_encode(n: int) -> bytes:
    # BER-OID (7 data bits/byte, continuation bit on all but the last byte) -
    # only used for the VTarget Pack targetId, nothing else in ST0903 wants this
    if n == 0:
        return bytes([0])
    groups = []
    while n > 0:
        groups.insert(0, n & 0x7F)
        n >>= 7
    return bytes(
        g | 0x80 if i < len(groups) - 1 else g
        for i, g in enumerate(groups)
    )


def minimal_be(n: int) -> bytes:
    # ST0903.6's "Vmax" format (Section 9.1): minimum 1 byte, maximum max
    # bytes, no internal length/continuation encoding - the enclosing TLV's
    # own BER length already says how many bytes to read
    if n == 0:
        return bytes([0])
    nbytes = (n.bit_length() + 7) // 8
    return n.to_bytes(nbytes, "big")


def pixel_number(row: int, col: int, frame_width: int) -> int:
    # ST0903.6 pixel-number convention: 1-based, row-major, top-left = 1
    return row * frame_width + col + 1


def _clamp_u8(v: int) -> int:
    return max(0, min(255, round(v)))


def encode_series(elements: list) -> bytes:
    # Series Type (Section 9.1.3): back-to-back [BER length][element], no
    # per-element tag - used for vTargetSeries/ontologySeries/vObjectSeries
    return b"".join(ber_length(len(e)) + e for e in elements)


def encode_ontology_ls(ontology_id: int, ontology_iri: str, entity_iri: str,
                        label: str) -> bytes:
    # Ontology LS (Table 17). ontologyId/ontologyIRI/entityIRI (tags 1/3/4)
    # are spec-mandatory; label (tag 6) is optional but the only one the
    # on-device renderer actually displays.
    return (
        tlv(1, minimal_be(ontology_id))
        + tlv(3, ontology_iri.encode("utf-8"))
        + tlv(4, entity_iri.encode("utf-8"))
        + tlv(6, label.encode("utf-8"))
    )


def encode_vobject_ls(ontology_id: int) -> bytes:
    # VObject LS (Table 12) - just an ontologyId (tag 3) pointing back into
    # the VMTI LS's own ontologySeries. VObject's own confidence item (tag 4)
    # is IMAPB/float-encoded and deliberately not implemented -
    # targetConfidenceLevel (VTarget Pack tag 5, plain uint8) already covers it.
    return tlv(3, minimal_be(ontology_id))


def encode_vmask(corners: list, frame_width: int) -> bytes:
    # VMask LS (Table 11, tag 101 inside a VTarget Pack - different namespace
    # than the outer tag 101/vTargetSeries). Body is a single pixelContour
    # field (tag 1): an Array of pixel numbers, each its own
    # [BER length][minimal-be value] pair, no further wrapping. corners is a
    # list of (row, col) tuples, clockwise order.
    points = b"".join(
        ber_length(len(minimal_be(pixel_number(row, col, frame_width))))
        + minimal_be(pixel_number(row, col, frame_width))
        for row, col in corners
    )
    return tlv(101, tlv(1, points))


def encode_vtarget(target_id: int, centroid_row: int, centroid_col: int,
                    bbox_tl_row: int, bbox_tl_col: int,
                    bbox_br_row: int, bbox_br_col: int,
                    frame_width: int, priority: int = None,
                    confidence: int = None, ontology_id: int = None) -> bytes:
    # One VTarget Pack (Table 10). Framing is [BER length of pack][BER-OID
    # targetId][TLV fields...] - length BEFORE the id, easy to get backwards.
    #
    # Sends centroid twice (packed pixel number tag 1, plus separate
    # centroidPixRow/Col tags 19/20) and the bbox twice (flat
    # boundingBoxTopLeft/BottomRight tags 2/3, plus a 4-corner VMask polygon
    # tag 101) - both representations are legal, maximizes decoder compatibility.
    #
    # priority/confidence (tags 4/5) are plain fixed 1-byte uint, not Vmax -
    # confidence is spec-defined 0-100, priority is implementation-defined,
    # sent here as raw 0-255. Both omitted entirely when None.
    #
    # ontology_id gets wrapped as a one-element vObjectSeries (tag 107)
    # pointing into the VMTI LS's ontologySeries - see build_vmti_local_set().
    corners = [
        (bbox_tl_row, bbox_tl_col),
        (bbox_tl_row, bbox_br_col),
        (bbox_br_row, bbox_br_col),
        (bbox_br_row, bbox_tl_col),
    ]
    pack = (
        ber_oid_encode(target_id)
        + tlv(1, minimal_be(pixel_number(centroid_row, centroid_col, frame_width)))
        + tlv(2, minimal_be(pixel_number(bbox_tl_row, bbox_tl_col, frame_width)))
        + tlv(3, minimal_be(pixel_number(bbox_br_row, bbox_br_col, frame_width)))
        + tlv(20, minimal_be(centroid_col))
        + tlv(19, minimal_be(centroid_row))
        + encode_vmask(corners, frame_width)
    )
    if priority is not None:
        pack += tlv(4, bytes([_clamp_u8(priority)]))
    if confidence is not None:
        pack += tlv(5, bytes([_clamp_u8(confidence)]))
    if ontology_id is not None:
        pack += tlv(107, encode_series([encode_vobject_ls(ontology_id)]))
    return ber_length(len(pack)) + pack


def build_vmti_local_set(frame_width: int, frame_height: int,
                          vtarget_packs: list, ontologies: list = None) -> bytes:
    # VMTI LS body (Table 9), goes under ST0601 tag 74. This is
    # "embedded-VMTI" (nested in a parent ST0601 packet) - checkSum (Item 1)
    # must be OMITTED for embedded-VMTI, only standalone-VMTI sends it, so
    # it's correctly never sent below.
    #
    # NOT sending vmtiLsVersionNum (tag 4): the spec calls it mandatory
    # (ST 0903.5-99), but it's a field the previously-proven-working encoder
    # (misb0601.py, confirmed rendering real boxes on-device) never sent
    # either, and pgscmedia has repeatedly turned out to be a non-generic,
    # quirky decoder rather than a fully spec-compliant TLV walker (see
    # e.g. VMTI_ENCODING_SCHEMA.md's BER-length-before-ID story). Prefer
    # exact parity with the last known-on-device-working wire format over
    # an unverified spec addition.
    #
    # frameWidth/frameHeight (tags 8/9) are Vmax (minimal-be), not a fixed
    # byte count - e.g. the spec's own 1920 example encodes to exactly 2 bytes.
    #
    # ontologies is an optional list of (ontology_id, ontology_iri, entity_iri,
    # label) tuples - a shared lookup table VObject.ontologyId references by
    # id, encoded as tag 103 (ontologySeries).
    num_targets = len(vtarget_packs)
    body = bytearray()
    body += tlv(3, b"JSON-TO-ST0903")                  # VMTI system name/description
    body += tlv(5, minimal_be(min(num_targets, 255)))  # total targets detected
    body += tlv(6, minimal_be(min(num_targets, 255)))  # number of reported targets
    body += tlv(8, minimal_be(frame_width))            # frame width (px)
    body += tlv(9, minimal_be(frame_height))           # frame height (px)
    body += tlv(101, b"".join(vtarget_packs))          # vTargetSeries
    if ontologies:
        body += tlv(103, encode_series(
            [encode_ontology_ls(*o) for o in ontologies]))
    return bytes(body)


def checksum(packet_without_checksum_value: bytes) -> bytes:
    # ST0601 16-bit rolling checksum: sum of 16-bit big-endian words over the
    # whole packet (key + length + value, checksum tag/length included but
    # not its own 2-byte value), mod 65536
    data = packet_without_checksum_value
    if len(data) % 2:
        data += b"\x00"
    total = 0
    for i in range(0, len(data), 2):
        total = (total + ((data[i] << 8) | data[i + 1])) & 0xFFFF
    return struct.pack(">H", total)


def build_packet(vmti_body: bytes, frame_center_lat=None, frame_center_lon=None) -> bytes:
    # One ST0601 UAS Datalink LS packet carrying vmti_body under tag 74.
    # frame_center_lat/lon (tags 23/24) are optional, unrelated to ST0903 -
    # forwarded through only if the JSON record happens to carry them.
    items = bytearray()
    if frame_center_lat is not None and frame_center_lon is not None:
        items += tlv(23, _scale_int(frame_center_lat, -90, 90, 4))
        items += tlv(24, _scale_int(frame_center_lon, -180, 180, 4))
    items += tlv(74, vmti_body)

    body_without_checksum = bytes(items)
    payload_len = len(body_without_checksum) + 4  # +4 for the checksum TLV itself
    header = UAS_LS_KEY + ber_length(payload_len)

    packet_minus_checksum_value = header + body_without_checksum + bytes([1, 2])
    cksum = checksum(packet_minus_checksum_value)
    return header + body_without_checksum + tlv(1, cksum)


# Pure-stdlib MPEG-TS/PES demux

def decode_pts(five_bytes):
    # standard MPEG-2 PES 33-bit PTS decode: three chunks, each dropping its
    # own low marker bit
    b0, b1, b2, b3, b4 = five_bytes
    pts = ((b0 >> 1) & 0x07) << 30
    pts |= (((b1 << 8) | b2) >> 1) << 15
    pts |= ((b3 << 8) | b4) >> 1
    return pts


def extract_pes_payload(pes_bytes):
    # strips the fixed 6-byte PES prefix + optional-fields block, returning
    # just the elementary-stream payload (the raw JSON bytes here) -
    # header_data_length (byte 8) works regardless of which optional fields
    # (PTS, DTS, ESCR...) are actually present
    header_data_length = pes_bytes[8]
    return bytes(pes_bytes[9 + header_data_length:])


def iter_pes_records(ts_path, pid):
    # yields (pts_ns_or_None, payload_bytes) per complete PES packet on `pid`.
    # Assumes the metadata stream is PES-wrapped private data with an
    # explicit, nonzero PES_packet_length - true for anything GStreamer's
    # mpegtsmux or any compliant encoder would produce.
    buf = None          # bytes accumulated for the PES packet being assembled
    want_len = None      # total PES packet length (6 + PES_packet_length), once known
    pts = None
    pts_read = False

    def finished():
        return buf is not None and want_len is not None and len(buf) >= want_len

    with open(ts_path, "rb") as f:
        while True:
            packet = f.read(TS_PACKET_SIZE)
            if len(packet) < TS_PACKET_SIZE:
                break
            if packet[0] != SYNC_BYTE:
                continue  # not attempting resync - shouldn't trigger against a well-formed file

            packet_pid = ((packet[1] & 0x1F) << 8) | packet[2]
            if packet_pid != pid:
                continue

            pusi = bool(packet[1] & 0x40)
            afc = (packet[3] >> 4) & 0x3
            off = 4
            if afc in (0x2, 0x3):
                off = 5 + packet[4]  # skip adaptation field
            if afc in (0x0, 0x2):
                continue  # no payload in this packet

            payload = packet[off:]

            if pusi:
                if finished():
                    yield pts, extract_pes_payload(bytes(buf[:want_len]))
                buf = bytearray()
                want_len = None
                pts = None
                pts_read = False

            if buf is None:
                continue  # payload arrived before we ever saw a PUSI - skip
            buf += payload

            if want_len is None and len(buf) >= 6:
                pes_packet_length = (buf[4] << 8) | buf[5]
                if pes_packet_length:
                    want_len = 6 + pes_packet_length

            if not pts_read and len(buf) >= 9:
                header_data_length = buf[8]
                if len(buf) >= 9 + header_data_length:
                    if buf[6] & 0xC0 == 0x80:  # '10' marker bits -> optional header present
                        pts_dts_flags = (buf[7] >> 6) & 0x3
                        if pts_dts_flags in (0x2, 0x3) and len(buf) >= 14:
                            pts_90k = decode_pts(buf[9:14])
                            pts = round(pts_90k * NS_PER_SEC / PTS_HZ)
                    pts_read = True

            if finished():
                yield pts, extract_pes_payload(bytes(buf[:want_len]))
                buf = None
                want_len = None
                pts = None
                pts_read = False

        if finished():
            yield pts, extract_pes_payload(bytes(buf[:want_len]))


# Auto-detect the metadata PID - with exactly one video + one metadata
# stream, it's just "whichever PMT entry isn't the video stream", no need
# for the caller to know or pass a PID

VIDEO_STREAM_TYPES = {0x01, 0x02, 0x10, 0x1B, 0x24}  # MPEG-1/2, MPEG-4, H.264, H.265


def _read_first_psi_section(ts_path, pid, max_packets=20000):
    # minimal single-packet PSI section reader - PAT/PMT are almost always
    # well under 184 bytes and fit in one TS packet
    with open(ts_path, "rb") as f:
        for _ in range(max_packets):
            packet = f.read(TS_PACKET_SIZE)
            if len(packet) < TS_PACKET_SIZE:
                break
            if packet[0] != SYNC_BYTE:
                continue
            packet_pid = ((packet[1] & 0x1F) << 8) | packet[2]
            if packet_pid != pid or not (packet[1] & 0x40):
                continue
            afc = (packet[3] >> 4) & 0x3
            off = 4
            if afc in (0x2, 0x3):
                off = 5 + packet[4]
            pointer_field = packet[off]
            start = off + 1 + pointer_field
            section_length = ((packet[start + 1] & 0x0F) << 8) | packet[start + 2]
            return packet[start:start + 3 + section_length][:-4]  # drop trailing CRC32
    return None


def find_metadata_pid(ts_path):
    # returns the PID of the non-video stream in the PMT; errors out if the
    # file doesn't have exactly one video + one other stream
    pat = _read_first_psi_section(ts_path, 0x0000)
    if not pat:
        sys.exit(f"[error] no PAT found in {ts_path}")

    pmt_pid = None
    for i in range(8, len(pat), 4):  # PAT program entries follow the 8-byte section header
        program_number = (pat[i] << 8) | pat[i + 1]
        if program_number != 0:  # skip the network-PID entry (program_number == 0)
            pmt_pid = ((pat[i + 2] & 0x1F) << 8) | pat[i + 3]
            break
    if pmt_pid is None:
        sys.exit(f"[error] no program found in {ts_path}'s PAT")

    pmt = _read_first_psi_section(ts_path, pmt_pid)
    if not pmt:
        sys.exit(f"[error] no PMT found on PID 0x{pmt_pid:x}")

    program_info_length = ((pmt[10] & 0x0F) << 8) | pmt[11]
    i = 12 + program_info_length
    other_pids = []
    while i + 5 <= len(pmt):
        stream_type = pmt[i]
        elementary_pid = ((pmt[i + 1] & 0x1F) << 8) | pmt[i + 2]
        es_info_length = ((pmt[i + 3] & 0x0F) << 8) | pmt[i + 4]
        if stream_type not in VIDEO_STREAM_TYPES:
            other_pids.append(elementary_pid)
        i += 5 + es_info_length

    if len(other_pids) != 1:
        sys.exit(f"[error] expected exactly one non-video stream in {ts_path}, "
                  f"found {len(other_pids)}: {[hex(p) for p in other_pids]}")
    return other_pids[0]


# JSON record -> real ST0601(+ST0903 VMTI) KLV packet
DEFAULT_FRAME_WIDTH = 1280
DEFAULT_FRAME_HEIGHT = 720
ONTOLOGY_IRI = "https://example.org/converted-vmti-ontology"

# Placeholder/guessed schema -- REPLACE to match the real format:
#   {
#     "frame_width": int, "frame_height": int,               # optional
#     "frame_center_lat": float, "frame_center_lon": float,   # optional
#     "targets": [
#       {
#         "id": int,
#         "bbox": [row_tl, col_tl, row_br, col_br],
#         "label": str,          # optional
#         "confidence": 0-100,   # optional
#         "priority": 0-255      # optional
#       },
#       ...
#     ]
#   }


def json_record_to_klv_packet(record: dict) -> bytes:
    frame_width = record.get("frame_width", DEFAULT_FRAME_WIDTH)
    frame_height = record.get("frame_height", DEFAULT_FRAME_HEIGHT)

    vtargets = []
    ontologies = []  # (ontology_id, ontology_iri, entity_iri, label)

    for i, t in enumerate(record.get("targets", [])):
        row_tl, col_tl, row_br, col_br = t["bbox"]
        centroid_row = (row_tl + row_br) // 2
        centroid_col = (col_tl + col_br) // 2

        # one ontology entry per labeled target (ontologyId just needs to be
        # unique within the packet) - no need to dedupe repeated labels
        # across targets, the string repeats at most a few times per packet
        label = t.get("label")
        ontology_id = i + 1 if label else None
        if label:
            ontologies.append((ontology_id, ONTOLOGY_IRI, f"{ONTOLOGY_IRI}#{label}", label))

        vtargets.append(encode_vtarget(
            t["id"], centroid_row, centroid_col,
            row_tl, col_tl, row_br, col_br,
            frame_width,
            priority=t.get("priority"),
            confidence=t.get("confidence"),
            ontology_id=ontology_id))

    vmti_body = build_vmti_local_set(
        frame_width, frame_height, vtargets, ontologies)

    return build_packet(
        vmti_body,
        frame_center_lat=record.get("frame_center_lat"),
        frame_center_lon=record.get("frame_center_lon"))


# Remux - video passthrough + freshly-encoded KLV, via GStreamer

# h264parse + explicit alignment=au - mpegtsmux fails to negotiate against
# tsdemux's raw byte-stream H.264 pad otherwise. Assumes H.264 video
# passthrough - for a different codec, swap h264parse/video/x-h264 for the
# matching parser+caps (e.g. h265parse/video/x-h265).
#
# is-live=true block=true on the KLV appsrc (not is-live=false as an earlier
# version of this had): matches the proven-working live pipeline exactly
# (webcam_klv_server.py) -- is-live affects how mpegtsmux computes each
# packet's PCR (Program Clock Reference), and an is-live=false mux was
# confirmed on-device to produce a KLV track pgscmedia's demuxer discovers
# fine (FORMAT_KLV shows up, video decodes) but never actually delivers a
# single decoded metadata unit from (VmtiOverlayLayer.metadataChanged()
# never fires) -- while the exact same content muxed is-live=true worked
# immediately. block=true gives the appsrc real backpressure instead of
# silently dropping buffers pushed faster than the mux can drain them.
OUTPUT_PIPELINE = (
    "filesrc location={in_path} ! tsdemux name=demux "
    "demux. ! queue ! h264parse config-interval=-1 "
    "! video/x-h264,stream-format=byte-stream,alignment=au ! mux. "
    "appsrc name=klvsrc format=time is-live=true block=true do-timestamp=false "
    "caps=meta/x-klv,parsed=(boolean)true "
    "! queue ! mux. "
    "mpegtsmux name=mux alignment=7 "
    "! filesink location={out_path}"
)


def convert(in_path, out_path):
    pid = find_metadata_pid(in_path)
    print(f"[convert] metadata PID: 0x{pid:x}")

    records = []
    for pts, payload in iter_pes_records(in_path, pid):
        try:
            record = json.loads(payload.decode("utf-8"))
            klv = json_record_to_klv_packet(record)
        except Exception as e:
            print(f"[warn] skipping malformed record at pts={pts}: {e}",
                  file=sys.stderr)
            continue
        records.append((pts, klv))

    if not records:
        sys.exit(f"[error] no JSON records decoded from PID 0x{pid:x} in {in_path}")

    print(f"[convert] {len(records)} JSON records -> ST0903 KLV packets")

    Gst.init(None)
    pipeline = Gst.parse_launch(
        OUTPUT_PIPELINE.format(in_path=in_path, out_path=out_path))
    appsrc = pipeline.get_by_name("klvsrc")

    loop = GLib.MainLoop()
    bus = pipeline.get_bus()
    bus.add_signal_watch()

    def on_message(_bus, message):
        if message.type == Gst.MessageType.EOS:
            print("[convert] done")
            loop.quit()
        elif message.type == Gst.MessageType.ERROR:
            err, debug = message.parse_error()
            print(f"[gst error] {err}: {debug}", file=sys.stderr)
            loop.quit()

    bus.connect("message", on_message)
    pipeline.set_state(Gst.State.PLAYING)

    # offline (non-live) conversion - push every record up front rather than
    # pacing against real time the way a live streaming server would
    fallback_pts = 0
    for pts, klv in records:
        buf = Gst.Buffer.new_wrapped(klv)
        buf.pts = pts if pts is not None else fallback_pts
        fallback_pts += Gst.SECOND  # 1s spacing if the source had no PTS at all
        appsrc.emit("push-buffer", buf)
    appsrc.emit("end-of-stream")

    loop.run()
    pipeline.set_state(Gst.State.NULL)


def main():
    if len(sys.argv) != 3:
        sys.exit(f"usage: {sys.argv[0]} input.ts output.ts")
    convert(sys.argv[1], sys.argv[2])


if __name__ == "__main__":
    main()
