"""python3 scripts/test_adnetwork_public_export.py"""
import contextlib
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(__file__))
import adnetwork_public_export as ex  # noqa: E402

GOOD = {
    "record_id": "r1", "network": "exoclick", "advertiser_key": "brand.example.com",
    "advertiser_name": "Example Brand", "evidence_url": "https://www.exoclick.com/advertisers/",
    "observed_at": "2026-09-05T09:00:00+09:00", "kind": "advertiser-page",
    "notes": "private note", "raw_json": "{\"secret\":1}", "ingested_at": "2026-09-05T00:00:00Z",
}


def row(**kw):
    r = dict(GOOD)
    r.update(kw)
    return r


class PublicExport(unittest.TestCase):
    def test_only_public_columns(self):
        e = ex.build([GOOD], "42", "2026-11-18T00:00:00Z")
        self.assertEqual(e["count"], 1)
        self.assertEqual(set(e["advertisers"][0]), set(ex.FIELDS))
        body = ex.encode(e).decode()
        for private in ("private note", "secret", "record_id", "ingested_at", "r1"):
            self.assertNotIn(private, body)

    def test_withheld(self):
        bad = [
            row(advertiser_name="Contact sales@brand.example.com"),
            row(evidence_url="http://www.exoclick.com/"),
            row(evidence_url="https://user:pw@exoclick.com/"),
            row(evidence_url="javascript:alert(1)"),
            row(advertiser_key="not a domain"),
            row(advertiser_key="Brand.Example.com"),
            row(observed_at="2026-09-05T09:00:00"),
            row(network=""),
            row(kind="Advertiser Page"),
            row(advertiser_name="   "),
            row(advertiser_name=7),
        ]
        e = ex.build(bad, None, "2026-11-18T00:00:00Z")
        self.assertEqual((e["count"], e["withheld"]), (0, len(bad)))

    def test_nullable_and_dedupe_and_order(self):
        e = ex.build([row(advertiser_key="z.example.com", kind=None, advertiser_name=None),
                      GOOD, row(record_id="r2")], None, "t")
        self.assertEqual(e["count"], 2)
        self.assertEqual([a["advertiserKey"] for a in e["advertisers"]],
                         ["brand.example.com", "z.example.com"])

    def test_digest_file(self):
        with tempfile.TemporaryDirectory() as d:
            digest = ex.write(ex.build([GOOD], None, "t"), d)
            with open(os.path.join(d, ex.FILE), "rb") as f:
                self.assertEqual(hashlib.sha256(f.read()).hexdigest(), digest)
            with open(os.path.join(d, ex.FILE + ".sha256")) as f:
                self.assertEqual(f.read(), f"{digest}  {ex.FILE}\n")
            with open(os.path.join(d, ex.FILE)) as f:
                self.assertEqual(json.load(f)["schema"], ex.SCHEMA)


def _make_candidate(base, with_module=True):
    d = os.path.join(base, "candidate")
    os.makedirs(d, exist_ok=True)
    if with_module:
        with open(os.path.join(d, "datalake_catalog.py"), "w") as f:
            f.write('"""stub for tests"""\n\ndef connect():\n'
                    '    raise AssertionError("readiness probe must not connect")\n')
    return d


class PathResolution(unittest.TestCase):
    def test_explicit_is_authoritative(self):
        self.assertEqual(ex.resolve_root_scripts("/some/where/else"), "/some/where/else")
        self.assertEqual(ex.resolve_root_scripts("/some/where/else", ["/a", "/b"]), "/some/where/else")

    def test_default_prefers_first_existing_module(self):
        with tempfile.TemporaryDirectory() as d:
            first = _make_candidate(d + "-1", with_module=False)
            second = _make_candidate(d + "-2", with_module=True)
            self.assertEqual(ex.resolve_root_scripts(None, [first, second]), second)

    def test_default_falls_back_to_first_candidate(self):
        with tempfile.TemporaryDirectory() as d:
            first = _make_candidate(d + "-1", with_module=False)
            second = _make_candidate(d + "-2", with_module=False)
            self.assertEqual(ex.resolve_root_scripts(None, [first, second]), first)

    def test_missing_module_message_names_module_and_location(self):
        with tempfile.TemporaryDirectory() as d:
            empty = _make_candidate(d + "-empty", with_module=False)
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                with self.assertRaises(SystemExit) as cm:
                    ex.catalog_rows(empty)
            self.assertEqual(cm.exception.code, 2)
            msg = err.getvalue()
            self.assertIn("datalake_catalog", msg)
            self.assertIn(os.path.join(empty, "datalake_catalog.py"), msg)


class ReadinessCheck(unittest.TestCase):
    def _probe(self, *, env_token, keychain_rc, root_scripts):
        env = {} if env_token is None else {"CF_CATALOG_TOKEN": env_token}
        proc = mock.MagicMock(returncode=keychain_rc, stdout="", stderr="")
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, env, clear=False), \
                mock.patch("adnetwork_public_export.subprocess") as sub, \
                contextlib.redirect_stdout(out), \
                contextlib.redirect_stderr(err):
            if env_token is None:
                os.environ.pop("CF_CATALOG_TOKEN", None)
            sub.run.return_value = proc
            rc = ex.check_readiness(root_scripts)
        return rc, out.getvalue() + err.getvalue()

    def test_absent_token_blocks_and_never_connects(self):
        with tempfile.TemporaryDirectory() as d:
            root = _make_candidate(d + "-absent")
            rc, text = self._probe(env_token=None, keychain_rc=1, root_scripts=root)
            self.assertEqual(rc, 2)
            self.assertIn("token=absent", text)
            self.assertIn("BLOCKED", text)

    def test_env_token_present_no_value_leaked(self):
        with tempfile.TemporaryDirectory() as d:
            root = _make_candidate(d + "-env")
            rc, text = self._probe(env_token="SECRET-VALUE-1234", keychain_rc=1, root_scripts=root)
            self.assertEqual(rc, 0)
            self.assertIn("token=present", text)
            self.assertIn("source=env:CF_CATALOG_TOKEN", text)
            self.assertIn("catalog_read=NOT_ATTEMPTED", text)
            self.assertNotIn("SECRET-VALUE-1234", text)

    def test_keychain_token_present(self):
        with tempfile.TemporaryDirectory() as d:
            root = _make_candidate(d + "-keychain")
            rc, text = self._probe(env_token=None, keychain_rc=0, root_scripts=root)
            self.assertEqual(rc, 0)
            self.assertIn("source=keychain:service=gftd.cf,account=API_TOKEN", text)

    def test_missing_module_blocks(self):
        with tempfile.TemporaryDirectory() as d:
            root = _make_candidate(d + "-missing", with_module=False)
            rc, text = self._probe(env_token=None, keychain_rc=1, root_scripts=root)
            self.assertEqual(rc, 2)
            self.assertIn("module=missing", text)
            self.assertIn("BLOCKED", text)

class OfflineRowsRoundTrip(unittest.TestCase):
    def test_rows_mode_byte_stable_and_digest_matches(self):
        rows = [GOOD, row(advertiser_key="z.example.com", kind=None, advertiser_name=None),
                row(advertiser_name="Contact sales@brand.example.com")]
        with tempfile.TemporaryDirectory() as d:
            rows_path = os.path.join(d, "rows.json")
            with open(rows_path, "w") as f:
                json.dump(rows, f)
            out1, out2 = os.path.join(d, "out1"), os.path.join(d, "out2")
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(ex.main(["--rows", rows_path, "--out", out1]), 0)
                self.assertEqual(ex.main(["--rows", rows_path, "--out", out2]), 0)
            with open(os.path.join(out1, ex.FILE), "rb") as f:
                body1 = f.read()
            with open(os.path.join(out2, ex.FILE), "rb") as f:
                body2 = f.read()
            # Same rows + same generatedAt must re-encode to the same bytes.
            export1 = json.loads(body1.decode())
            self.assertEqual((export1["count"], export1["withheld"]), (2, 1))
            rebuilt = ex.encode(ex.build(rows, None, export1["generatedAt"]))
            self.assertEqual(rebuilt, body1)
            # Advertisers payload identical across the two runs.
            export2 = json.loads(body2.decode())
            self.assertEqual(export2["advertisers"], export1["advertisers"])
            self.assertEqual(export2["count"], export1["count"])
            digest = hashlib.sha256(body1).hexdigest()
            with open(os.path.join(out1, ex.FILE + ".sha256")) as f:
                self.assertEqual(f.read(), f"{digest}  {ex.FILE}\n")


if __name__ == "__main__":
    unittest.main()
