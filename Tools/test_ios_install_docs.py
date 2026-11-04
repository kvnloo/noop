"""Source-level regression checks for iOS installation guidance in secondary guides.

The iOS app can be installed from an unsigned release IPA or built from source.
These checks pin the five guides that previously described iOS as source-only.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

# Six iOS passages in five guides: the contribution guide has both an
# architecture summary and a platform roadmap entry.
IOS_PASSAGES = (
    ("docs/ANDROID.md", "(A third target, **iOS**", "results match macOS.)"),
    ("docs/ARCHITECTURE.md", "**iOS** app", "and the **Android** app"),
    ("docs/CONTRIBUTING.md", "\x60android/\x60, and **iOS was folded", "All reuse the same packages"),
    ("docs/CONTRIBUTING.md", "- **iOS (", "on-hardware testing is especially welcome"),
    ("docs/CROSS_PLATFORM.md", "| **iOS** |", "\n"),
    (
        "docs/DATA_MODEL.md",
        "storage code back both the macOS app and the iOS app",
        "The macOS app target opens",
    ),
)
OBSOLETE_CLAIM = re.compile(
    r"build[- ]from[- ]source[- ]only|build[- ]it[- ]yourself only|"
    r"no pre[- ]built downloads|only way to run it",
    re.IGNORECASE,
)


class IOSInstallDocumentationTests(unittest.TestCase):
    def passage(self, relative: str, start: str, end: str) -> str:
        text = (ROOT / relative).read_text(encoding="utf-8")
        self.assertIn(start, text, f"iOS passage missing in {relative}")
        remainder = text.split(start, 1)[1]
        self.assertIn(end, remainder, f"iOS passage end missing in {relative}")
        return start + remainder.split(end, 1)[0]

    def test_all_secondary_guides_offer_sideload_and_source_options(self) -> None:
        for path, start, end in IOS_PASSAGES:
            with self.subTest(guide=path, section=start):
                passage = self.passage(path, start, end)
                self.assertIn(".ipa", passage)
                self.assertRegex(passage, r"(?i)\bsource\b")
                self.assertIsNone(
                    OBSOLETE_CLAIM.search(passage),
                    f"obsolete source-only iOS claim in {path}",
                )

    def test_install_links_resolve_to_canonical_guide(self) -> None:
        self.assertTrue((ROOT / "docs/IOS.md").is_file())
        for path in (
            "docs/ANDROID.md",
            "docs/ARCHITECTURE.md",
            "docs/CONTRIBUTING.md",
            "docs/DATA_MODEL.md",
        ):
            with self.subTest(guide=path):
                text = (ROOT / path).read_text(encoding="utf-8")
                self.assertRegex(text, r"\]\((?:\./)?IOS\.md\)")

    def test_canonical_guide_keeps_both_install_paths(self) -> None:
        guide = (ROOT / "docs/IOS.md").read_text(encoding="utf-8")
        self.assertIn("## Install (sideload)", guide)
        self.assertIn("## Build from source", guide)
        self.assertIn("AltStore", guide)
        self.assertIn("SideStore", guide)
        self.assertIn(".ipa", guide)
        self.assertIn(
            "https://github.com/ryanbr/noop/releases",
            guide,
        )


if __name__ == "__main__":
    unittest.main()
