import struct

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
    # fixed-length signed field scaling - ST0601 top-level fields only
    # (frame center, corner offsets), unrelated to ST0903
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
                          vtarget_packs: list, ontologies: list = None,
                          system_name: bytes = b"CUAS-TEST-VMTI") -> bytes:
    # VMTI LS body (Table 9), goes under ST0601 tag 74. This is
    # "embedded-VMTI" (nested in a parent ST0601 packet) - checkSum (Item 1)
    # must be OMITTED for embedded-VMTI, only standalone-VMTI sends it, so
    # it's correctly never sent below.
    #
    # frameWidth/frameHeight (tags 8/9) are Vmax (minimal-be), not a fixed
    # byte count - e.g. the spec's own 1920 example encodes to exactly 2 bytes.
    #
    # ontologies is an optional list of (ontology_id, ontology_iri, entity_iri,
    # label) tuples - a shared lookup table VObject.ontologyId references by
    # id, encoded as tag 103 (ontologySeries).
    num_targets = len(vtarget_packs)
    body = bytearray()
    body += tlv(3, system_name)                        # VMTI system name/description
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


def build_packet(vmti_body: bytes, frame_center_lat: float = None,
                  frame_center_lon: float = None, corner_offsets: list = None) -> bytes:
    """One ST0601 UAS Datalink LS packet carrying vmti_body under tag 74.
    frame_center_lat/lon (tags 23/24) are optional. corner_offsets, if
    given, is a list of four (lat_off, lon_off) tuples (tags 26-33, range
    +-0.075 deg) - the sensor footprint square ATAK draws on the map
    alongside the frame-center/SPI marker."""
    items = bytearray()
    if frame_center_lat is not None and frame_center_lon is not None:
        items += tlv(23, _scale_int(frame_center_lat, -90, 90, 4))
        items += tlv(24, _scale_int(frame_center_lon, -180, 180, 4))
    if corner_offsets:
        for i, (lat_off, lon_off) in enumerate(corner_offsets):
            items += tlv(26 + i * 2, _scale_int(lat_off, -0.075, 0.075, 2))
            items += tlv(27 + i * 2, _scale_int(lon_off, -0.075, 0.075, 2))
    items += tlv(74, vmti_body)

    body_without_checksum = bytes(items)
    payload_len = len(body_without_checksum) + 4  # +4 for the checksum TLV itself
    header = UAS_LS_KEY + ber_length(payload_len)

    packet_minus_checksum_value = header + body_without_checksum + bytes([1, 2])
    cksum = checksum(packet_minus_checksum_value)
    return header + body_without_checksum + tlv(1, cksum)
