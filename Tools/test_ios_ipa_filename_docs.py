"""Keep NOOP's iOS installation filename aligned with the published release source."""

import json
import unittest
from pathlib import Path
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[1]
IPA_NAME_TEMPLATE = "NOOP-ios-unsigned-v<version>.ipa"
DOC_OCCURRENCES = (
    ("docs/IOS.md", 2),
    ("README.md", 1),
)


class IOSIPAFilenameDocumentationTests(unittest.TestCase):
    def test_latest_release_source_points_to_unsigned_ipa(self) -> None:
        source = json.loads((ROOT / "altstore-source.json").read_text(encoding="utf-8"))
        latest = source["apps"][0]["versions"][0]
        version = latest["version"]
        self.assertEqual(
            urlsplit(latest["downloadURL"]).path,
            f"/ryanbr/noop/releases/download/v{version}/NOOP-ios-unsigned-v{version}.ipa",
        )

    def test_install_guides_use_current_asset_filename_template(self) -> None:
        for path, expected_count in DOC_OCCURRENCES:
            with self.subTest(guide=path):
                guide = (ROOT / path).read_text(encoding="utf-8")
                self.assertEqual(guide.count(IPA_NAME_TEMPLATE), expected_count)
                self.assertNotIn("NOOP-v<version>-ios.ipa", guide)
                self.assertNotIn("NOOP-vX-ios.ipa", guide)


if __name__ == "__main__":
    unittest.main()
