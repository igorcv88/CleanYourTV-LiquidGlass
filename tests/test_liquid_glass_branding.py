"""Visible Liquid Glass branding must not rename persisted theme identifiers."""

import unittest
from pathlib import Path
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "launcher/app/src/main/java/com/gathin/tvhome"
RES = ROOT / "launcher/app/src/main/res"


class LiquidGlassBrandingTest(unittest.TestCase):
    def test_theme_picker_displays_liquid_glass_but_keeps_id(self):
        source = (JAVA / "ThemeCatalog.java").read_text(encoding="utf-8")
        self.assertIn('"sweden", "scenic-glass"', source)
        self.assertIn('"Sweden", "Liquid Glass"', source)
        self.assertNotIn('"Scenic Glass"', source)
        self.assertTrue((ROOT / "themes/scenic-glass/config.json").is_file())

    def test_visible_settings_copy_uses_new_theme_name(self):
        source = (JAVA / "ScenicHome.java").read_text(encoding="utf-8")
        self.assertIn('row("Home layout", "Liquid Glass"', source)
        self.assertIn('"data to return to Liquid Glass."', source)
        self.assertNotIn('"Scenic Glass"', source)
        self.assertNotIn('"No account details are read by Scenic."', source)
        self.assertNotIn('"Restore replaces current Scenic settings', source)

    def test_app_label_and_help_use_same_brand_in_every_locale(self):
        resource_files = sorted(RES.glob("values*/strings.xml"))
        self.assertGreaterEqual(len(resource_files), 20)
        for path in resource_files:
            with self.subTest(locale=path.parent.name):
                root = ElementTree.parse(path).getroot()
                app_name = root.find("string[@name='app_name']")
                # Some locale folders intentionally inherit the base app name.
                if app_name is not None:
                    self.assertEqual("Cleaner TV Liquid Glass", app_name.text)
                help_text = root.find("string[@name='home_settings_help']")
                if help_text is not None:
                    self.assertNotIn("CleanYourTV", help_text.text or "")

    def test_legacy_settings_keys_remain_backwards_compatible(self):
        config = (JAVA / "Config.java").read_text(encoding="utf-8")
        theme = (ROOT / "themes/scenic-glass/config.json").read_text(encoding="utf-8")
        prefs = (JAVA / "ScenicHome.java").read_text(encoding="utf-8")
        self.assertIn('"scenic_home"', config)
        self.assertIn('"scenic_home": true', theme)
        self.assertIn('"scenic_home"', prefs)

    def test_manual_workflow_name_matches_brand(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn("name: Cleaner TV Liquid Glass — Publish full Setup release", workflow)


if __name__ == "__main__":
    unittest.main()
