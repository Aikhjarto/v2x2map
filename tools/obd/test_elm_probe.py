"""Offline tests for elm_probe (no adapter needed):  python3 -m unittest tools/obd/test_elm_probe.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from elm_probe import Refused, check_allowed, describe, iso_tp  # noqa: E402


class Safety(unittest.TestCase):
    def test_reading_is_allowed(self):
        for c in ("ATZ", "at sh 713", "ATCRA77D", "ATSP6", "ATMA", "010D", "0902", "03",
                  "22F187", "22 F1 87 F1 89", "1003", "1001", "3E00", "1902FF"):
            check_allowed(c)

    def test_everything_else_is_refused(self):
        for c in ("2EF19801", "14FFFFFF", "1101", "3101FF00", "2F123403", "2701", "1002",
                  "04", "2803", "8502", "22F1", "ATPP0CSV", "ATWM", "ATBRD23", "xyz", "", "ATMT"):
            with self.assertRaises(Refused, msg=c):
                check_allowed(c)


class Parsing(unittest.TestCase):
    def test_single_frame(self):
        self.assertEqual([[0x62, 0xF1, 0x87, 0x41]], iso_tp(["77D 04 62 F1 87 41"]))

    def test_multi_frame_and_foreign_ids(self):
        lines = ["77D 10 0E 62 F1 87 38 57 30", "7E8 03 41 0D 32", "77D 21 39 30 37 31 31 33 41", "77D 22 20 00 00"]
        m = iso_tp(lines, "77D")
        self.assertEqual(1, len(m))
        self.assertEqual("62 F1 87 38 57 30 39 30 37 31 31 33 41 20", describe(m[0]).split("   ")[0])
        self.assertIn("'8W0907113A '", describe(m[0]))

    def test_negative_answer(self):
        self.assertEqual("negative 22: 31 requestOutOfRange", describe(iso_tp(["77D 03 7F 22 31"])[0]))

    def test_noise_lines_are_ignored(self):
        self.assertEqual([], iso_tp(["SEARCHING...", "NO DATA", "CAN ERROR", "?"]))


if __name__ == "__main__":
    unittest.main()
