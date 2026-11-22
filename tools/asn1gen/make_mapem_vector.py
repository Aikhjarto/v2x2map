#!/usr/bin/env python3
"""
Appends a synthetic MAPEM (no real data) with lane geometry and signal groups to the decoder test vectors:

    venv/bin/python tools/asn1gen/make_mapem_vector.py android/app/src/test/resources/its_vectors.tsv

Intersection 4711 (same as make_spatem_vector.py) at 48.2° N, 14.3° E, right-hand traffic. Node offsets
are in cm (x east, y north); the first node of a lane is relative to the reference point and at the
stop line, every further node is relative to the previous one.

    lane 1  ingress from the south (northbound), x=+4.5 m: straight → lane 5, right → lane 7, signal group 2
    lane 2  ingress from the south, left-turn lane, x=+1.5 m: left → lane 6, signal group 3
    lane 3  ingress from the west (eastbound), y=-3 m: straight → lane 7, signal group 4
    lane 4  ingress from the east (westbound), y=+3 m: straight → lane 6, signal group 4
    lane 5  egress to the north, lane 6 egress to the west, lane 7 egress to the east
    lane 8  crosswalk on the south arm (no connections; must be ignored for vehicles)
"""
import importlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare_pycrate import canon                      # noqa: E402
from pycrate_asn1rt.codecs import ASN1CodecPER          # noqa: E402

ASN1CodecPER.GET_DEFVAL = False
T = importlib.import_module("pycrate_asn1dir.ITS_IS").GLOBAL.MOD["MAPEM-PDU-Descriptions"]["MAPEM"]

INGRESS = (2, 2)        # LaneDirection bits: ingressPath
EGRESS = (1, 2)         # egressPath
STRAIGHT, LEFT, RIGHT = (0b100000000000, 12), (0b010000000000, 12), (0b001000000000, 12)


def node(x, y):
    size = next(n for n, lim in ((1, 511), (2, 1023), (3, 2047), (4, 4095), (5, 8191), (6, 32767))
                if abs(x) <= lim and abs(y) <= lim)
    return {"delta": (f"node-XY{size}", {"x": x, "y": y})}


def lane(lane_id, direction, nodes, approach=None, connects=(), kind=("vehicle", (0, 8)), maneuvers=None):
    g = {"laneID": lane_id,
         "laneAttributes": {"directionalUse": direction, "sharedWith": (0, 10), "laneType": kind},
         "nodeList": ("nodes", [node(*n) for n in nodes])}
    if approach is not None:
        g["ingressApproach" if direction == INGRESS else "egressApproach"] = approach
    if maneuvers is not None:
        g["maneuvers"] = maneuvers
    if connects:
        g["connectsTo"] = [{"connectingLane": {"lane": to, "maneuver": m}, "signalGroup": sg} for to, m, sg in connects]
    return g


T.set_val({"header": {"protocolVersion": 2, "messageID": 5, "stationID": 123456},
           "map": {"msgIssueRevision": 3, "intersections": [{
               "id": {"region": 0, "id": 4711}, "revision": 3,
               "refPoint": {"lat": 482000000, "long": 143000000},
               "laneWidth": 300,
               "laneSet": [
                   lane(1, INGRESS, [(450, -1200), (0, -3000)], 1, [(5, STRAIGHT, 2), (7, RIGHT, 2)],
                        maneuvers=(STRAIGHT[0] | RIGHT[0], 12)),
                   lane(2, INGRESS, [(150, -1200), (0, -3000)], 1, [(6, LEFT, 3)], maneuvers=LEFT),
                   lane(3, INGRESS, [(-1200, -300), (-3000, 0)], 2, [(7, STRAIGHT, 4)]),
                   lane(4, INGRESS, [(1200, 300), (3000, 0)], 3, [(6, STRAIGHT, 4)]),
                   lane(5, EGRESS, [(300, 1200), (0, 3000)], 4),
                   lane(6, EGRESS, [(-1200, 300), (-3000, 0)], 2),
                   lane(7, EGRESS, [(1200, -300), (3000, 0)], 3),
                   lane(8, (3, 2), [(-600, -900), (1200, 0)], kind=("crosswalk", (0, 16))),
               ]}]}})
enc = T.to_uper()
T.from_uper(enc)
with open(sys.argv[1], "a") as f:
    f.write(f"MAPEM\t{enc.hex()}\t{json.dumps(canon(T.get_val()), separators=(',', ':'), ensure_ascii=False)}\n")
print("MAPEM", len(enc), "bytes")
