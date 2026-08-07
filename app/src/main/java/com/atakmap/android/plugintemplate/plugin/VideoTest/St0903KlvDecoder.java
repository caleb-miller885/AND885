package com.atakmap.android.plugintemplate.plugin.VideoTest;

import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class St0903KlvDecoder {

    static final class TargetExtra {
        final int priority;   // 0-255, -1 if not sent
        final int confidence; // 0-100, -1 if not sent
        final String label;   // null if not sent / not resolvable

        TargetExtra(int priority, int confidence, String label) {
            this.priority = priority;
            this.confidence = confidence;
            this.label = label;
        }
    }

    private static final int VMTI_LS_TAG_IN_ST0601 = 74;
    private static final int TAG_VTARGET_SERIES = 101;
    private static final int TAG_ONTOLOGY_SERIES = 103;
    private static final int TAG_TARGET_PRIORITY = 4;
    private static final int TAG_TARGET_CONFIDENCE = 5;
    private static final int TAG_VOBJECT_SERIES = 107;
    private static final int TAG_VOBJECT_ONTOLOGY_ID = 3;
    private static final int TAG_ONTOLOGY_ID = 1;
    private static final int TAG_ONTOLOGY_ENTITY_IRI = 4;
    private static final int TAG_ONTOLOGY_LABEL = 6;

    private St0903KlvDecoder() {
    }

    //Implemented directly from the ST0903 standard
    static Map<Integer, TargetExtra> parse(byte[] uasLsValue) {
        try {
            Map<Integer, byte[]> top = walkTlv(uasLsValue);
            byte[] vmtiBody = top.get(VMTI_LS_TAG_IN_ST0601);
            if (vmtiBody == null)
                return Collections.emptyMap();

            Map<Integer, byte[]> vmti = walkTlv(vmtiBody);
            byte[] targetSeries = vmti.get(TAG_VTARGET_SERIES);
            if (targetSeries == null)
                return Collections.emptyMap();

            Map<Integer, String> labelsByOntologyId =
                    parseOntologySeries(vmti.get(TAG_ONTOLOGY_SERIES));

            Map<Integer, TargetExtra> out = new HashMap<>();
            int off = 0;
            while (off < targetSeries.length) {
                Cursor packLen = berLength(targetSeries, off);
                int packEnd = packLen.pos + (int) packLen.value;
                if (packEnd > targetSeries.length)
                    break;

                Cursor id = berOid(targetSeries, packLen.pos);
                int targetId = (int) id.value;
                Map<Integer, byte[]> pack = walkTlv(targetSeries, id.pos, packEnd);

                int priority = pack.containsKey(TAG_TARGET_PRIORITY)
                        ? unsigned(pack.get(TAG_TARGET_PRIORITY)[0]) : -1;
                int confidence = pack.containsKey(TAG_TARGET_CONFIDENCE)
                        ? unsigned(pack.get(TAG_TARGET_CONFIDENCE)[0]) : -1;
                String label = resolveLabel(pack.get(TAG_VOBJECT_SERIES), labelsByOntologyId);

                if (priority >= 0 || confidence >= 0 || label != null)
                    out.put(targetId, new TargetExtra(priority, confidence, label));

                off = packEnd;
            }
            return out;
        } catch (RuntimeException e) {
            Log.w("St0903RawFields", "parse: malformed/unrecognized metadata, skipping extras", e);
            return Collections.emptyMap();
        }
    }

    // Series framing (vTargetSeries/ontologySeries/vObjectSeries, ST0903.6
    // Table 9/Figure 12): back-to-back [BER length][body], no per-element tag.
    private static Map<Integer, String> parseOntologySeries(byte[] series) {
        Map<Integer, String> out = new HashMap<>();
        if (series == null)
            return out;
        int off = 0;
        while (off < series.length) {
            Cursor len = berLength(series, off);
            int end = len.pos + (int) len.value;
            if (end > series.length)
                break;
            Map<Integer, byte[]> ontology = walkTlv(series, len.pos, end);
            byte[] idBytes = ontology.get(TAG_ONTOLOGY_ID);
            byte[] labelBytes = ontology.containsKey(TAG_ONTOLOGY_LABEL)
                    ? ontology.get(TAG_ONTOLOGY_LABEL) : ontology.get(TAG_ONTOLOGY_ENTITY_IRI);
            if (idBytes != null && labelBytes != null) {
                out.put((int) minimalBe(idBytes),
                        friendlyLabel(new String(labelBytes, StandardCharsets.UTF_8)));
            }
            off = end;
        }
        return out;
    }

    // A VTarget's vObjectSeries can carry multiple VObject candidates
    // (ST0903.6 allows several labels per target); only the first is used since the overlay draws one label per box.
    private static String resolveLabel(byte[] vObjectSeries, Map<Integer, String> labelsByOntologyId) {
        if (vObjectSeries == null || vObjectSeries.length == 0)
            return null;
        Cursor len = berLength(vObjectSeries, 0);
        int end = len.pos + (int) len.value;
        if (end > vObjectSeries.length)
            return null;
        Map<Integer, byte[]> vObject = walkTlv(vObjectSeries, len.pos, end);
        byte[] ontologyIdBytes = vObject.get(TAG_VOBJECT_ONTOLOGY_ID);
        return ontologyIdBytes != null
                ? labelsByOntologyId.get((int) minimalBe(ontologyIdBytes)) : null;
    }

    // Ontology IRIs are full URIs (e.g. ".../cuas-demo-ontology#Vehicle");
    // display just the fragment/last path segment when one is present, else
    // the whole string (covers the entityIRI fallback above when a sender
    // omits the optional label/tag-6 field).
    private static String friendlyLabel(String iriOrLabel) {
        int frag = iriOrLabel.lastIndexOf('#');
        if (frag >= 0 && frag < iriOrLabel.length() - 1)
            return iriOrLabel.substring(frag + 1);
        int slash = iriOrLabel.lastIndexOf('/');
        if (slash >= 0 && slash < iriOrLabel.length() - 1)
            return iriOrLabel.substring(slash + 1);
        return iriOrLabel;
    }

    private static int unsigned(byte b) {
        return b & 0xFF;
    }

    // Minimal-byte-count big-endian (see misb0601.py minimal_be_encode):
    // plain unsigned accumulate over however many bytes the TLV's own
    // length said to read -- no internal length/continuation encoding.
    private static long minimalBe(byte[] value) {
        long v = 0;
        for (byte b : value)
            v = (v << 8) | (b & 0xFF);
        return v;
    }

    private static final class Cursor {
        final long value;
        final int pos;
        Cursor(long value, int pos) {
            this.value = value;
            this.pos = pos;
        }
    }

    // BER length: short form (top bit clear, byte itself is the length) or
    // long form (top bit set, low 7 bits = count of following big-endian
    // length bytes).
    private static Cursor berLength(byte[] data, int off) {
        int b0 = data[off] & 0xFF;
        if ((b0 & 0x80) == 0)
            return new Cursor(b0, off + 1);
        int n = b0 & 0x7F;
        long v = 0;
        for (int i = 0; i < n; i++)
            v = (v << 8) | (data[off + 1 + i] & 0xFF);
        return new Cursor(v, off + 1 + n);
    }

    // BER-OID (ISO/IEC 8825): 7 data bits/byte, continuation bit set on all
    // but the last byte -- used only for VTarget Pack target IDs.
    private static Cursor berOid(byte[] data, int off) {
        long v = 0;
        int pos = off;
        while (true) {
            int b = data[pos] & 0xFF;
            v = (v << 7) | (b & 0x7F);
            pos++;
            if ((b & 0x80) == 0)
                break;
        }
        return new Cursor(v, pos);
    }

    // Flat TLV walk over a whole Local Set body: single-byte tag, BER
    // length, value -- repeated until the range is consumed. Local Set
    // items are unique per tag at a given scope, so a plain map is
    // sufficient (no ordering/repetition to preserve).
    private static Map<Integer, byte[]> walkTlv(byte[] data) {
        return walkTlv(data, 0, data.length);
    }

    private static Map<Integer, byte[]> walkTlv(byte[] data, int start, int end) {
        Map<Integer, byte[]> out = new HashMap<>();
        int off = start;
        while (off < end) {
            int tag = data[off] & 0xFF;
            off++;
            Cursor len = berLength(data, off);
            off = len.pos;
            int valEnd = off + (int) len.value;
            if (valEnd > end)
                break;
            out.put(tag, Arrays.copyOfRange(data, off, valEnd));
            off = valEnd;
        }
        return out;
    }
}
