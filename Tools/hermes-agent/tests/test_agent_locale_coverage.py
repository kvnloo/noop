"""All shipped Android locales must carry the experimental agent access strings."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
RES = ROOT / "android/app/src/main/res"
LOCALES = ("values", "values-de", "values-es", "values-fr", "values-it",
           "values-pl", "values-pt-rPT", "values-ru", "values-zh")
FORMAT = re.compile(r"%\d+\$[sd]")


class AgentLocaleCoverageTest(unittest.TestCase):
    def test_all_shipped_locales_have_identical_keys_and_formats(self):
        expected = None
        for directory in LOCALES:
            path = RES / directory / "agent_access.xml"
            self.assertTrue(path.is_file(), str(path))
            entries = [(e.attrib["name"], e.text or "") for e in ET.parse(path).getroot()]
            keys = [k for k, _ in entries]
            self.assertEqual(len(keys), len(set(keys)), directory)
            self.assertTrue(all(v.strip() for _, v in entries), directory)
            formats = {k: sorted(FORMAT.findall(v)) for k, v in entries}
            if expected is None:
                expected = formats
            else:
                self.assertEqual(expected, formats, directory)


if __name__ == "__main__":
    unittest.main()
