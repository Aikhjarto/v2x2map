#!/usr/bin/env python3
"""
Read-only probing of a car through an ELM327 adapter (Bluetooth RFCOMM or serial device).

Built to check what V2X2MAP could get from the car besides standard OBD values (speed for a
green-light speed advisory, steering angle, turn signals). Saved automatically to
~/v2x2map-recordings/obd/ (contains the VIN — keep it out of the repository): elm-<what>-<time>.txt with
the printed results and elm-<what>-<time>.log with every byte sent and received, both written as they happen.

Runs on a Linux computer that talks to the ELM327 directly — no phone app (AndrOBD) in between; the adapter
takes only one connection, so close AndrOBD first. Pair once with `bluetoothctl` (scan on, pair <MAC>,
PIN usually 1234 or 0000), then pass the MAC with --bt.

SAFETY: only reading requests leave this script. Every command goes through `check_allowed()`:
  AT commands from a fixed list (adapter settings only), OBD modes 01/02/03/07/09/0A (read),
  UDS 22 (ReadDataByIdentifier), 19 02/19 0A (read DTCs), 10 01/10 03 (default/extended session —
  never 10 02 programming), 3E 00 (tester present). Writing, clearing, resetting, routines,
  actuator tests and security access are refused, even if typed in `raw`.
Use it with the car parked and the ignition on (engine running for long sessions).

Examples
    python3 tools/obd/elm_probe.py --bt 00:1D:A5:68:98:8B info
    python3 tools/obd/elm_probe.py --bt 00:1D:A5:68:98:8B ecus            # part numbers of known VAG units
    python3 tools/obd/elm_probe.py --bt ... ecus --scan                     # try every 11-bit ID 700-7EF
    python3 tools/obd/elm_probe.py --bt ... monitor --seconds 10            # does the gateway pass bus traffic?
    python3 tools/obd/elm_probe.py --bt ... dids --ecu 713:77D --from 0000 --to 0FFF
    python3 tools/obd/elm_probe.py --bt ... watch --ecu 713:77D --dids 1234,5678   # turn the wheel and look
    python3 tools/obd/elm_probe.py --bt ... raw "ATRV" "010D"
"""
import argparse
import datetime
import os
import re
import socket
import sys
import time

# ---------------------------------------------------------------- safety

AT_ALLOWED = {
    "Z", "WS", "I", "@1", "RV", "DP", "DPN", "E0", "E1", "L0", "L1", "S0", "S1", "H0", "H1",
    "CAF0", "CAF1", "AT0", "AT1", "AT2", "MA", "AR", "D", "CFC0", "CFC1",
}
AT_PREFIX_ALLOWED = ("SP", "TP", "SH", "CRA", "ST", "FCSH", "FCSD", "FCSM", "CF", "CM")
OBD_READ_MODES = {"01", "02", "03", "07", "09", "0A"}
UDS_ALLOWED = {
    "22": None,                 # ReadDataByIdentifier, any DID
    "19": {"02", "0A"},         # ReadDTCInformation: by status mask, supported DTCs
    "10": {"01", "03"},         # session: default, extended (never 02 = programming)
    "3E": {"00", "80"},         # tester present
}


class Refused(Exception):
    pass


def check_allowed(cmd: str) -> str:
    """Returns the normalised command or raises Refused. The only gate to the adapter."""
    c = re.sub(r"\s+", "", cmd).upper()
    if not c:
        raise Refused("empty command")
    if c.startswith("AT"):
        a = c[2:]
        if a in AT_ALLOWED or a.startswith(AT_PREFIX_ALLOWED):
            return c
        raise Refused(f"AT command not on the list: {cmd}")
    if not re.fullmatch(r"[0-9A-F]+", c) or len(c) % 2:
        raise Refused(f"not a hex request: {cmd}")
    sid, sub = c[:2], c[2:4]
    if sid in OBD_READ_MODES:
        return c
    if sid in UDS_ALLOWED:
        subs = UDS_ALLOWED[sid]
        if subs is None:
            if sid == "22" and len(c) >= 6 and (len(c) - 2) % 4 == 0:
                return c
            raise Refused(f"22 needs one or more 2-byte DIDs: {cmd}")
        if sub in subs:
            return c
    raise Refused(f"service {sid} is not read-only or not allowed: {cmd}")


# ---------------------------------------------------------------- transport

class Elm:
    def __init__(self, bt=None, channel=1, dev=None, baud=38400, log=None):
        self.log = log
        if bt:
            self.sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
            self.sock.connect((bt, channel))
            self.sock.settimeout(0.2)
            self._read = lambda: self.sock.recv(1024)
            self._write = self.sock.sendall
        else:
            import serial  # pyserial
            self.ser = serial.Serial(dev, baud, timeout=0.2)
            self._read = lambda: self.ser.read(1024)
            self._write = self.ser.write

    def _log(self, direction, text):
        if self.log:
            self.log.write(f"{datetime.datetime.now().isoformat(timespec='milliseconds')} {direction} {text!r}\n")
            self.log.flush()

    def cmd(self, command: str, timeout=5.0) -> list:
        """Sends one allowed command, returns the response lines (prompt '>' stripped)."""
        c = check_allowed(command)
        self._log(">>", c)
        self._write((c + "\r").encode())
        buf = b""
        end = time.time() + timeout
        while time.time() < end:
            try:
                chunk = self._read()
            except socket.timeout:
                chunk = b""
            if chunk:
                buf += chunk
                if b">" in buf:
                    break
        text = buf.decode(errors="replace").replace(">", "")
        self._log("<<", text)
        return [l.strip() for l in re.split(r"[\r\n]+", text) if l.strip() and l.strip() != c]

    def stop(self):
        """Interrupts a running ATMA by sending a bare CR (not a command)."""
        self._log(">>", "<CR> (stop)")
        self._write(b"\r")
        time.sleep(0.3)
        try:
            self._read()
        except socket.timeout:
            pass


# ---------------------------------------------------------------- parsing

NEGATIVE = {
    "10": "generalReject", "11": "serviceNotSupported", "12": "subFunctionNotSupported",
    "13": "incorrectMessageLength", "22": "conditionsNotCorrect", "31": "requestOutOfRange",
    "33": "securityAccessDenied", "78": "responsePending", "7E": "subFunctionNotSupportedInActiveSession",
    "7F": "serviceNotSupportedInActiveSession",
}


def iso_tp(lines: list, resp_id: str = None) -> list:
    """Reassembles ISO-TP payloads from ELM lines with headers (ATH1, spaces on).
    Returns a list of byte lists, one per complete message."""
    msgs, cur, need = [], None, 0
    for line in lines:
        parts = line.split()
        if len(parts) < 2 or not re.fullmatch(r"[0-9A-F]{3}", parts[0]):
            continue
        if resp_id and parts[0] != resp_id.upper():
            continue
        try:
            data = [int(x, 16) for x in parts[1:]]
        except ValueError:
            continue
        pci = data[0] >> 4
        if pci == 0:                                   # single frame
            msgs.append(data[1:1 + (data[0] & 0x0F)])
        elif pci == 1:                                 # first frame
            need = ((data[0] & 0x0F) << 8) | data[1]
            cur = data[2:]
        elif pci == 2 and cur is not None:             # consecutive frame
            cur += data[1:]
            if len(cur) >= need:
                msgs.append(cur[:need]); cur = None
    return msgs


def describe(msg: list) -> str:
    if not msg:
        return "(empty)"
    if msg[0] == 0x7F and len(msg) >= 3:
        code = f"{msg[2]:02X}"
        return f"negative {msg[1]:02X}: {code} {NEGATIVE.get(code, '')}"
    hexs = " ".join(f"{b:02X}" for b in msg)
    asc = "".join(chr(b) if 32 <= b < 127 else "." for b in msg[3:]) if msg[0] == 0x62 else ""
    return hexs + (f"   '{asc}'" if asc.strip(". ") else "")


# ---------------------------------------------------------------- probes

# VAG 11-bit diagnostic IDs as commonly documented — the point of `ecus` is to verify them.
VAG_ECUS = [
    ("01 engine", "7E0", "7E8"), ("02 gearbox", "7E1", "7E9"), ("03 ABS/ESP", "713", "77D"),
    ("15 airbag", "715", "77F"), ("17 instruments", "714", "77E"), ("19 gateway", "710", "77A"),
    ("44 steering assist", "712", "77C"),
]
ID_DIDS = {"F187": "part number", "F189": "software version", "F197": "system name"}


def setup(elm, headers=True):
    for c in ("ATZ", "ATE0", "ATL0", "ATS1", "ATH1" if headers else "ATH0", "ATSP0", "ATAT1"):
        elm.cmd(c, timeout=8 if c == "ATZ" else 3)


def target(elm, req, resp):
    elm.cmd(f"ATSH{req}"); elm.cmd(f"ATCRA{resp}")
    # flow control for multi-frame answers to the unit we are talking to (ELM v1.4+; ignored by some clones)
    if elm.cmd("ATFCSH" + req) != ["?"]:
        elm.cmd("ATFCSD300000"); elm.cmd("ATFCSM1")


def cmd_info(elm, a):
    print("adapter:", elm.cmd("ATI"), "voltage:", elm.cmd("ATRV"))
    print("0100 →", elm.cmd("0100", timeout=10))
    print("protocol:", elm.cmd("ATDP"), elm.cmd("ATDPN"))
    for pid in ("0100", "0120", "0140", "0160", "0180", "01A0"):
        msgs = iso_tp(elm.cmd(pid))
        for m in msgs:
            if m and m[0] == 0x41 and len(m) >= 6:
                base = int(pid[2:], 16)
                bits = int.from_bytes(bytes(m[2:6]), "big")
                sup = [f"{base + i + 1:02X}" for i in range(32) if bits & (1 << (31 - i))]
                print(f"supported PIDs {pid}: {' '.join(sup)}")
    for pid, name in (("010D", "speed km/h"), ("010C", "rpm×4"), ("0146", "ambient °C+40"), ("0902", "VIN")):
        print(f"{name}: {[describe(m) for m in iso_tp(elm.cmd(pid))]}")


def cmd_ecus(elm, a):
    units = VAG_ECUS
    if a.scan:
        units = [(f"id {i:03X}", f"{i:03X}", None) for i in range(0x700, 0x7F0) if i not in (0x7DF,)]
    for name, req, resp in units:
        if resp:
            target(elm, req, resp)
        else:                                         # scan: accept any answer, see who replies
            elm.cmd(f"ATSH{req}"); elm.cmd("ATAR")
        lines = elm.cmd("22F187", timeout=1.5)
        heads = sorted({l.split()[0] for l in lines if re.match(r"^[0-9A-F]{3} ", l)})
        msgs = iso_tp(lines)
        if msgs or not a.scan:
            print(f"{name:20s} {req}→{','.join(heads) or resp or '-'}: {[describe(m) for m in msgs] or lines}")
            if msgs and resp:
                for did in ("F189", "F197"):
                    print(f"{'':20s} {ID_DIDS[did]}: {[describe(m) for m in iso_tp(elm.cmd('22' + did), resp)]}")
    elm.cmd("ATAR")


def cmd_monitor(elm, a):
    elm.cmd("ATSH7DF"); elm.cmd("ATAR")
    print(f"listening {a.seconds} s (ATMA) — with a gateway expect nothing")
    elm._log(">>", "ATMA")
    elm._write(b"ATMA\r")
    end, n, ids = time.time() + a.seconds, 0, {}
    buf = b""
    while time.time() < end:
        try:
            buf += elm._read()
        except socket.timeout:
            pass
        *lines, buf = buf.split(b"\r")
        for l in lines:
            s = l.decode(errors="replace").strip()
            if re.match(r"^[0-9A-F]{3} ", s):
                n += 1; ids[s[:3]] = ids.get(s[:3], 0) + 1
            elif s:
                print("  ", s)
    elm.stop()
    print(f"{n} frames from {len(ids)} IDs: {dict(sorted(ids.items()))}")


def parse_ecu(s):
    req, resp = s.upper().split(":")
    return req, resp


def cmd_dids(elm, a):
    req, resp = parse_ecu(a.ecu)
    target(elm, req, resp)
    if a.extended:
        print("10 03 →", [describe(m) for m in iso_tp(elm.cmd("1003"), resp)])
    last_tp = time.time()
    for did in range(int(a.start, 16), int(a.end, 16) + 1):
        if a.extended and time.time() - last_tp > 2:
            elm.cmd("3E00"); last_tp = time.time()
        msgs = iso_tp(elm.cmd(f"22{did:04X}", timeout=1.5), resp)
        for m in msgs:
            if m and m[0] == 0x62:
                print(f"{did:04X}: {describe(m)}")
            elif a.verbose:
                print(f"{did:04X}: {describe(m)}")


def cmd_watch(elm, a):
    req, resp = parse_ecu(a.ecu)
    target(elm, req, resp)
    if a.extended:
        elm.cmd("1003")
    dids = [d.strip().upper() for d in a.dids.split(",") if d.strip()]
    last, last_tp = {}, time.time()
    print("Ctrl+C to stop; only changes are printed")
    try:
        while True:
            for d in dids:
                m = (iso_tp(elm.cmd("22" + d, timeout=1.5), resp) or [[]])[0]
                v = describe(m)
                if last.get(d) != v:
                    print(f"{time.strftime('%H:%M:%S')} {d}: {v}"); last[d] = v
            if a.extended and time.time() - last_tp > 2:
                elm.cmd("3E00"); last_tp = time.time()
    except KeyboardInterrupt:
        pass


def cmd_raw(elm, a):
    for c in a.commands:
        try:
            lines = elm.cmd(c)
            print(c, "→", lines)
        except Refused as e:
            print("REFUSED:", e)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument("--bt", help="Bluetooth address of the ELM327 (RFCOMM, pair it first)")
    g.add_argument("--dev", help="serial device, e.g. /dev/rfcomm0 or /dev/ttyUSB0 (needs pyserial)")
    p.add_argument("--channel", type=int, default=1, help="RFCOMM channel (default 1)")
    p.add_argument("--logdir", default=os.path.expanduser("~/v2x2map-recordings/obd"))
    sub = p.add_subparsers(dest="what", required=True)
    sub.add_parser("info", help="adapter, protocol, supported standard PIDs, speed, VIN")
    e = sub.add_parser("ecus", help="part numbers of known VAG units (verifies CAN IDs)")
    e.add_argument("--scan", action="store_true", help="try every request ID 700-7EF")
    m = sub.add_parser("monitor", help="listen to the bus (ATMA)")
    m.add_argument("--seconds", type=float, default=10)
    d = sub.add_parser("dids", help="read a range of DIDs with service 22")
    d.add_argument("--ecu", required=True, help="request:response ID, e.g. 713:77D")
    d.add_argument("--from", dest="start", default="0000"); d.add_argument("--to", dest="end", default="00FF")
    d.add_argument("--extended", action="store_true", help="open the extended session (10 03) first")
    d.add_argument("-v", "--verbose", action="store_true", help="also print negative answers")
    w = sub.add_parser("watch", help="poll DIDs and print changes (e.g. while turning the wheel)")
    w.add_argument("--ecu", required=True); w.add_argument("--dids", required=True)
    w.add_argument("--extended", action="store_true")
    r = sub.add_parser("raw", help="send single allowed commands")
    r.add_argument("commands", nargs="+")
    a = p.parse_args()

    os.makedirs(a.logdir, exist_ok=True)
    stem = os.path.join(a.logdir, f"elm-{a.what}-{datetime.datetime.now():%Y%m%d-%H%M%S}")
    # Autosave: the raw adapter traffic goes to .log, everything printed (the results) also to .txt,
    # both flushed line by line, so nothing is lost on Ctrl+C, a dropped Bluetooth link or a crash.
    with open(stem + ".log", "w") as log, open(stem + ".txt", "w") as out:
        sys.stdout = Tee(sys.__stdout__, out)
        print("saving to:", stem + ".txt", "and", stem + ".log")
        print("command:", " ".join(sys.argv[1:]))
        elm = None
        try:
            elm = Elm(bt=a.bt, channel=a.channel, dev=a.dev, log=log)
            setup(elm)
            {"info": cmd_info, "ecus": cmd_ecus, "monitor": cmd_monitor, "dids": cmd_dids,
             "watch": cmd_watch, "raw": cmd_raw}[a.what](elm, a)
        except KeyboardInterrupt:
            print("stopped (Ctrl+C)")
        except Exception as e:
            print(f"ERROR: {type(e).__name__}: {e}")
            return 1
        finally:
            if elm and a.what in ("dids", "watch") and a.extended:
                try:
                    elm.cmd("1001")                   # back to the default session
                except Exception:
                    pass
            print("saved:", stem + ".txt")
            sys.stdout = sys.__stdout__
    return 0


class Tee:
    """Writes to the terminal and to the results file, flushing every write."""
    def __init__(self, *streams):
        self.streams = streams

    def write(self, s):
        for st in self.streams:
            st.write(s); st.flush()

    def flush(self):
        for st in self.streams:
            st.flush()

if __name__ == "__main__":
    sys.exit(main())
