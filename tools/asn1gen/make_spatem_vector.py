#!/usr/bin/env python3
"""
Appends a synthetic SPATEM (no real data) with signal timing to the decoder test vectors:

    venv/bin/python tools/asn1gen/make_spatem_vector.py android/app/src/test/resources/its_vectors.tsv

Intersection 4711; signal group 2 red (stop-And-Remain) with likelyTime 12345 (TimeMark, tenths of
a second in the hour), followed by group 5 green — SpatemParser must use the first movement.
"""
import importlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare_pycrate import canon                      # noqa: E402
from pycrate_asn1rt.codecs import ASN1CodecPER          # noqa: E402

ASN1CodecPER.GET_DEFVAL = False
T = importlib.import_module("pycrate_asn1dir.ITS_IS").GLOBAL.MOD["SPATEM-PDU-Descriptions"]["SPATEM"]
T.set_val({"header": {"protocolVersion": 2, "messageID": 4, "stationID": 123456},
           "spat": {"timeStamp": 432100, "intersections": [{
               "id": {"region": 0, "id": 4711}, "revision": 3, "status": (0, 16), "moy": 432100, "timeStamp": 30000,
               "states": [
                   {"signalGroup": 2, "state-time-speed": [{"eventState": "stop-And-Remain",
                    "timing": {"minEndTime": 12300, "maxEndTime": 12400, "likelyTime": 12345, "confidence": 10}}]},
                   {"signalGroup": 5, "state-time-speed": [{"eventState": "protected-Movement-Allowed",
                    "timing": {"minEndTime": 12500}}]}]}]}})
enc = T.to_uper()
T.from_uper(enc)
with open(sys.argv[1], "a") as f:
    f.write(f"SPATEM\t{enc.hex()}\t{json.dumps(canon(T.get_val()), separators=(',', ':'), ensure_ascii=False)}\n")
print("SPATEM", len(enc), "bytes")
