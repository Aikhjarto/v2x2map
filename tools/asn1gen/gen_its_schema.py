#!/usr/bin/env python3
"""
Generates the compact ASN.1 type tables used by the app's generic UPER/OER decoder
(android/app/src/main/assets/its_asn1/<set>.json) from the official ETSI / ISO / IEEE ASN.1
definitions as compiled by pycrate (https://github.com/pycrate-org/pycrate).

pycrate is only needed here, at development time; the app does not contain it.

    python3 -m venv venv && venv/bin/pip install pycrate
    venv/bin/python tools/asn1gen/gen_its_schema.py android/app/src/main/assets/its_asn1

Each pycrate module set has to be loaded in its own process (they share a global registry),
so the script re-invokes itself once per set.

Output format (all lists, to keep the files small):
  {"set": name, "roots": {"CAM": tid, ...}, "types": [node, ...]}
  node kinds (last element = ASN.1 type name or ""):
    ["seq", ext, [[name, tid, flag, default], ...], extNest, tn]
         flag 0 = mandatory, 1 = OPTIONAL, 2 = DEFAULT; extNest items: [name, tid] or
         ["[[", [[name, tid, flag, default], ...]] for an extension addition group
    ["ch",  ext, [[name, tid, tagClass, tagNum], ...], [[name, tid, tagClass, tagNum], ...], tn]
    ["int", lb, ub, ext, [[name, value], ...], tn]   lb/ub null when unbounded; named numbers
    ["enum", [[name, value], ...], [[name, value], ...], ext, tn]   root in PER index order
    ["bool", tn] / ["null", tn] / ["open", tn]
    ["bits", lb, ub, ext, [[name, bit], ...], tn]
    ["oct", lb, ub, ext, tn]
    ["str", kind, lb, ub, ext, alphabet, tn]  kind: IA5 Visible Printable Numeric UTF8 BMP Universal
    ["sof", tid, lb, ub, ext, tn]
"""
import importlib
import json
import os
import subprocess
import sys

SETS = {
    # set name -> [(root name, module, type)]
    "cam": ("ITS_CAM_2", [("CAM", "CAM-PDU-Descriptions", "CAM")]),
    "denm": ("ITS_DENM_3", [("DENM", "DENM-PDU-Descriptions", "DENM")]),
    "iso": ("ITS_IS", [("SPATEM", "SPATEM-PDU-Descriptions", "SPATEM"),
                       ("MAPEM", "MAPEM-PDU-Descriptions", "MAPEM"),
                       ("IVIM", "IVIM-PDU-Descriptions", "IVIM"),
                       ("SREM", "SREM-PDU-Descriptions", "SREM"),
                       ("SSEM", "SSEM-PDU-Descriptions", "SSEM"),
                       ("RTCMEM", "RTCMEM-PDU-Descriptions", "RTCMEM")]),
    "vam": ("ITS_VAM_3", [("VAM", "VAM-PDU-Descriptions", "VAM")]),
    "sec": ("ITS_IEEE1609_2", [("Ieee1609Dot2Data", "Ieee1609Dot2", "Ieee1609Dot2Data")]),
}

STR_KINDS = {"IA5String": "IA5", "VisibleString": "Visible", "PrintableString": "Printable",
             "NumericString": "Numeric", "UTF8String": "UTF8", "BMPString": "BMP",
             "UniversalString": "Universal", "ISO646String": "Visible"}


def num(x):
    """Integers beyond 2^53 as strings, so JSON readers that use doubles stay exact."""
    return str(x) if isinstance(x, int) and abs(x) > 2 ** 53 else x


def bounds(const):
    if not const:
        return None, None, 0
    return num(const.lb), num(const.ub), 1 if const.ext is not None else 0


def tname(o):
    r = getattr(o, "_typeref", None)
    if r is None:
        return ""
    called = getattr(r, "called", None)
    if isinstance(called, tuple) and len(called) == 2 and isinstance(called[1], str):
        return called[1]
    return str(r).split(".")[-1].rstrip(")")


class Gen:
    def __init__(self):
        self.types, self.index, self.stack = [], {}, {}

    def tid(self, o):
        key = id(o)
        if key in self.stack:          # recursive type: refer to the slot reserved for it
            return self.stack[key]
        slot = len(self.types)
        self.types.append(None)
        self.stack[key] = slot
        node = self.node(o)
        del self.stack[key]
        canon = json.dumps(node, separators=(",", ":"))
        if canon in self.index and not self._referenced(slot):
            self.types.pop()           # identical node already emitted: reuse it
            return self.index[canon]
        self.types[slot] = node
        self.index.setdefault(canon, slot)
        return slot

    def _referenced(self, slot):
        return slot != len(self.types) - 1

    def comp(self, parent, name):
        c = parent._cont[name]
        if name in getattr(parent, "_root_opt", []) or []:
            flag = 2 if c._def is not None else 1
        else:
            flag = 0
        default = None if c._def is None else repr(c._def)
        return [name, self.tid(c), flag, default]

    def node(self, o):
        t, tn = o.TYPE, tname(o)
        if t == "SEQUENCE":
            ext = 0 if o._ext is None else 1
            root = [self.comp(o, n) for n in o._root]
            nest = []
            if o._ext is not None:
                for e in o._ext_nest:
                    if isinstance(e, list):
                        g = o._ext_group_obj[o._ext_ident[e[0]]]
                        nest.append(["[[", [self.comp(g, n) for n in g._root]])
                    else:
                        nest.append([e, self.tid(o._cont[e])])
            return ["seq", ext, root, nest, tn]
        if t == "CHOICE":
            # _cont_tags maps (tag class, tag number) -> alternative name (pycrate ASN1Dict)
            tags = {v: k for k, v in o._cont_tags.items()} if o._cont_tags else {}

            def alt(n):
                cls, num = tags.get(n, (2, 0))
                return [n, self.tid(o._cont[n]), cls, num]
            return ["ch", 0 if o._ext is None else 1, [alt(n) for n in o._root],
                    [alt(n) for n in (o._ext or [])], tn]
        if t == "INTEGER":
            lb, ub, ext = bounds(o._const_val)
            named = sorted(([k, v] for k, v in (o._cont or {}).items()), key=lambda x: x[1]) if o._cont else []
            return ["int", lb, ub, ext, [[k, num(v)] for k, v in named], tn]
        if t == "ENUMERATED":
            return ["enum", [[n, o._cont[n]] for n in o._root], [[n, o._cont[n]] for n in (o._ext or [])],
                    0 if o._ext is None else 1, tn]
        if t == "BOOLEAN":
            return ["bool", tn]
        if t == "NULL":
            return ["null", tn]
        if t in ("OPEN_TYPE", "ANY"):
            return ["open", tn]
        if t == "BIT STRING":
            lb, ub, ext = bounds(o._const_sz)
            named = sorted(([k, v] for k, v in (o._cont or {}).items()), key=lambda x: x[1]) if o._cont else []
            return ["bits", lb, ub, ext, named, tn]
        if t == "OCTET STRING":
            lb, ub, ext = bounds(o._const_sz)
            return ["oct", lb, ub, ext, tn]
        if t in STR_KINDS:
            lb, ub, ext = bounds(o._const_sz)
            alpha = None
            ca = getattr(o, "_const_alpha", None)
            if ca is not None and ca.ext is None and ca.root:
                alpha = "".join(sorted(set("".join(str(x) for x in ca.root))))
                print(f"  note: alphabet constraint on {tn or o._name}: {alpha!r}", file=sys.stderr)
            return ["str", STR_KINDS[t], lb, ub, ext, alpha, tn]
        if t in ("SEQUENCE OF", "SET OF"):
            lb, ub, ext = bounds(o._const_sz)
            return ["sof", self.tid(o._cont), lb, ub, ext, tn]
        raise SystemExit(f"unsupported ASN.1 type {t} ({tn or o._name})")


def gen_one(set_name, out_dir):
    modset, roots = SETS[set_name]
    G = importlib.import_module("pycrate_asn1dir." + modset).GLOBAL.MOD
    sys.setrecursionlimit(20000)
    g = Gen()
    rmap = {name: g.tid(G[mod][typ]) for name, mod, typ in roots}
    path = os.path.join(out_dir, set_name + ".json")
    with open(path, "w") as f:
        json.dump({"set": set_name, "source": "pycrate_asn1dir." + modset, "roots": rmap, "types": g.types},
                  f, separators=(",", ":"))
    print(f"{set_name}: {len(g.types)} types -> {path} ({os.path.getsize(path) // 1024} kB)")


if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    if len(sys.argv) > 2:
        gen_one(sys.argv[2], out)
    else:
        for s in SETS:
            subprocess.run([sys.executable, "-I", os.path.abspath(__file__), out, s], check=True)
