#!/usr/bin/env python3
"""
Builds reference cases for the app's ASN.1 decoder from pcap recordings: extracts every ITS
message (UPER) and every IEEE 1609.2 envelope (OER), decodes them with pycrate and writes
    <root>\\t<hex>\\t<canonical json>
lines (canonical JSON as in AsnJson.kt). The Kotlin test DecoderVsPycrateTest decodes the same
hex and must produce identical JSON. Run per module set (separate processes):

    venv/bin/python tools/asn1gen/compare_pycrate.py cases.tsv rec1.pcap rec2.pcap ...

The cases contain real positions/IDs from the recordings: keep them out of the repository.
"""
import importlib
import json
import os
import struct
import subprocess
import sys

UPER_SETS = {"CAM": ("ITS_CAM_2", "CAM-PDU-Descriptions"), "DENM": ("ITS_DENM_3", "DENM-PDU-Descriptions"),
             "IVIM": ("ITS_IS", "IVIM-PDU-Descriptions"), "SPATEM": ("ITS_IS", "SPATEM-PDU-Descriptions"),
             "MAPEM": ("ITS_IS", "MAPEM-PDU-Descriptions"), "SREM": ("ITS_IS", "SREM-PDU-Descriptions"),
             "SSEM": ("ITS_IS", "SSEM-PDU-Descriptions"), "VAM": ("ITS_VAM_3", "VAM-PDU-Descriptions")}
PORTS = {2001: "CAM", 2002: "DENM", 2003: "MAPEM", 2004: "SPATEM", 2006: "IVIM", 2007: "SREM", 2008: "SSEM", 2018: "VAM"}


def frames(path):
    d = open(path, "rb").read()
    off = 24
    while off + 16 <= len(d):
        _, _, incl, _ = struct.unpack("<IIII", d[off:off + 16])
        off += 16
        yield d[off:off + incl]
        off += incl


def oer_len(b, i):
    f = b[i]
    if f < 0x80:
        return f, i + 1
    n = f & 0x7F
    return int.from_bytes(b[i + 1:i + 1 + n], "big"), i + 1 + n


def split(p):
    """-> (envelope bytes or None, (msg name, pdu bytes) or None)"""
    i = p.find(b"\xaa\xaa\x03\x00\x00\x00\x89\x47")
    if i < 0:
        return None, None
    gn = i + 8
    env, inner, end = None, gn + 4, len(p)
    if p[gn] & 0x0F == 2:
        env = bytes(p[gn + 4:])
        if env[:2] == b"\x03\x81" and env[3] & 0x40 and env[4:6] == b"\x03\x80":
            ln, k = oer_len(env, 6)
        elif env[:2] == b"\x03\x80":
            ln, k = oer_len(env, 2)
        else:
            return env, None
        inner, end = gn + 4 + k, gn + 4 + k + ln
    ht = p[inner + 1] >> 4
    ext = {1: 24, 2: 48, 3: 56, 4: 44, 5: 28, 6: 36}.get(ht, 28)
    btp = inner + 8 + ext
    port = struct.unpack(">H", p[btp:btp + 2])[0]
    name = PORTS.get(port)
    return env, ((name, bytes(p[btp + 4:end])) if name else None)


def canon(v):
    if isinstance(v, dict):
        return {k: canon(x) for k, x in v.items()}
    if isinstance(v, tuple):
        if len(v) == 2 and isinstance(v[0], str):
            return {v[0]: canon(v[1])}
        if len(v) == 2 and isinstance(v[0], int) and isinstance(v[1], int):
            return "b:" + (bin(v[0])[2:].zfill(v[1]) if v[1] else "")
    if isinstance(v, (bytes, bytearray)):
        return "h:" + bytes(v).hex()
    if isinstance(v, list):
        return [canon(x) for x in v]
    return v


def run_set(modset, out, pcaps):
    from pycrate_asn1rt.codecs import ASN1CodecPER, ASN1CodecOER
    ASN1CodecPER.GET_DEFVAL = False
    try:
        ASN1CodecOER.GET_DEFVAL = False
    except Exception:
        pass
    G = importlib.import_module("pycrate_asn1dir." + modset).GLOBAL.MOD
    n = 0
    with open(out, "a") as f:
        for path in pcaps:
            for p in frames(path):
                env, msg = split(p)
                if modset == "ITS_IEEE1609_2" and env is not None:
                    t = G["Ieee1609Dot2"]["Ieee1609Dot2Data"]
                    try:
                        t.from_oer(env)
                    except Exception as e:
                        print("pycrate OER failed:", type(e).__name__, str(e)[:80], file=sys.stderr)
                        continue
                    used = len(t.to_oer())
                    f.write(f"Ieee1609Dot2Data\t{env[:used].hex()}\t{json.dumps(canon(t.get_val()), separators=(',', ':'), ensure_ascii=False)}\n")
                    n += 1
                elif msg is not None and UPER_SETS.get(msg[0], (None,))[0] == modset:
                    name, pdu = msg
                    t = G[UPER_SETS[name][1]][name]
                    try:
                        t.from_uper(pdu)
                    except Exception as e:
                        print(f"pycrate {name} failed:", type(e).__name__, file=sys.stderr)
                        continue
                    f.write(f"{name}\t{pdu.hex()}\t{json.dumps(canon(t.get_val()), separators=(',', ':'), ensure_ascii=False)}\n")
                    n += 1
    print(f"{modset}: {n} cases")


if __name__ == "__main__":
    if sys.argv[1] == "--set":
        run_set(sys.argv[2], sys.argv[3], sys.argv[4:])
    else:
        out, pcaps = sys.argv[1], sys.argv[2:]
        open(out, "w").close()
        for s in sorted({v[0] for v in UPER_SETS.values()} | {"ITS_IEEE1609_2"}):
            subprocess.run([sys.executable, "-I", os.path.abspath(__file__), "--set", s, out, *pcaps], check=True)
