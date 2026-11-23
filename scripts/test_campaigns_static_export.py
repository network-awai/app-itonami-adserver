"""python3 -m unittest scripts/test_campaigns_static_export.py"""
import hashlib
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import campaigns_static_export as ex  # noqa: E402

GOOD = {
    "id": "cmp-1", "advertiser": "did:key:zADV",
    "creative": {"type": "image", "image-url": "https://cdn.example/ad1.png",
                 "click-url": "https://advertiser.example/x"},
    "bid": {"model": "cpm", "usd": "4.00"},
    "targeting": {"tier": "adult", "formats": ["rectangle"],
                  "placements": ["scene-detail"]},
    "budget": {"total-usd": "50.00", "spent-micros": 0},
    "status": "active",
}


def row(**kw):
    r = dict(GOOD)
    r.update(kw)
    return r


class CampaignsStaticExport(unittest.TestCase):
    def test_round_trip_empty(self):
        e = ex.build([], "2026-11-23T00:00:00Z", "abc")
        self.assertEqual((e["count"], e["withheld"], e["campaigns"]), (0, 0, []))
        self.assertEqual(e["generatedAt"], "2026-11-23T00:00:00Z")
        self.assertEqual(e["source"]["var"], "CAMPAIGNS_JSON")
        self.assertEqual(e["source"]["inputsSha256"], "abc")

    def test_round_trip_with_rows_sorted_and_deduped(self):
        e = ex.build([row(id="cmp-2"), GOOD, row(id="cmp-2")],
                     "2026-11-23T00:00:00Z", "abc")
        self.assertEqual(e["count"], 2)
        self.assertEqual([c["id"] for c in e["campaigns"]], ["cmp-1", "cmp-2"])

    def test_withheld(self):
        bad = [row(id=""), row(id=7), row(status="running"),
               row(bid="cpm"), row(advertiser="sales@brand.example.com"),
               "not-a-dict"]
        e = ex.build(bad, "t", "abc")
        self.assertEqual((e["count"], e["withheld"]), (0, len(bad)))

    def test_catalog_derived(self):
        e = ex.build([GOOD, row(id="cmp-2", targeting={"placements": ["go-wait", "scene-detail"]})],
                     "t", "abc")
        c = ex.build_catalog(e, "t")
        self.assertEqual(c["campaigns"], 2)
        self.assertEqual(c["slots"], ["go-wait", "scene-detail"])
        self.assertIn("campaigns.json", c["registry"])

    def test_digest_files(self):
        with tempfile.TemporaryDirectory() as d:
            e = ex.build([GOOD], "t", "abc")
            cat = ex.build_catalog(e, "t")
            d1, d2 = ex.write(e, cat, d)
            for fn, digest in ((ex.CAMPAIGNS_FILE, d1), (ex.CATALOG_FILE, d2)):
                with open(os.path.join(d, fn), "rb") as f:
                    self.assertEqual(hashlib.sha256(f.read()).hexdigest(), digest)
                with open(os.path.join(d, fn + ".sha256")) as f:
                    self.assertEqual(f.read(), f"{digest}  {fn}\n")
            with open(os.path.join(d, ex.CAMPAIGNS_FILE)) as f:
                self.assertEqual(json.load(f)["schema"], ex.SCHEMA)

    def test_wrangler_source_parses(self):
        # The real source of truth in this repo must parse and be a list.
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        rows, digest = ex.read_wrangler_registry(os.path.join(root, "wrangler.jsonc"))
        self.assertIsInstance(rows, list)
        self.assertEqual(len(digest), 64)


if __name__ == "__main__":
    unittest.main()
