#!/usr/bin/env python3
"""
Creates the anonymised test vectors in android/app/src/test/resources/its_vectors.tsv:
takes the largest message of a type from pcap recordings, replaces every position
(latitude/longitude, path deltas) and station ID with fixed fake values, re-encodes it with
pycrate and writes  <root>\t<hex>\t<canonical json>  (as compare_pycrate.py).

    venv/bin/python tools/asn1gen/make_vectors.py CAM  out.tsv rec1.pcap ...
    venv/bin/python tools/asn1gen/make_vectors.py DENM out.tsv rec1.pcap ...
    venv/bin/python tools/asn1gen/make_vectors.py IVIM out.tsv rec1.pcap ...

Each call appends one line; check the result for leftover coordinates before committing.
"""
import os
import sys, json, importlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare_pycrate import frames, split, canon, UPER_SETS
from pycrate_asn1rt.codecs import ASN1CodecPER
ASN1CodecPER.GET_DEFVAL = False
want, out_path, pcaps = sys.argv[1], sys.argv[2], sys.argv[3:]
mod, pdu_mod = UPER_SETS[want]
G = importlib.import_module("pycrate_asn1dir." + mod).GLOBAL.MOD
t = G[pdu_mod][want]

def scrub(v):
    if isinstance(v, dict):
        out = {}
        for k, x in v.items():
            kl = k.lower()
            if kl in ("latitude",): out[k] = 482000000
            elif kl in ("longitude",): out[k] = 143000000
            elif "stationid" in kl or kl == "originatingstationid": out[k] = 123456
            elif kl in ("deltalatitude", "deltalongitude"): out[k] = 0
            else: out[k] = scrub(x)
        return out
    if isinstance(v, list): return [scrub(x) for x in v]
    if isinstance(v, tuple) and len(v) == 2 and isinstance(v[0], str): return (v[0], scrub(v[1]))
    return v

best = None
for path in pcaps:
    for p in frames(path):
        env, msg = split(p)
        if not msg or msg[0] != want: continue
        t.from_uper(msg[1])
        if best is None or len(msg[1]) > len(best): best = msg[1]   # largest = most fields
t.from_uper(best)
t.set_val(scrub(t.get_val()))
enc = t.to_uper()
t.from_uper(enc)
with open(out_path, "a") as f:
    f.write(f"{want}\t{enc.hex()}\t{json.dumps(canon(t.get_val()), separators=(',', ':'), ensure_ascii=False)}\n")
print(want, len(enc), "bytes")
