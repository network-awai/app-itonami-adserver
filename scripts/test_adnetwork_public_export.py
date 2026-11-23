"""python3 scripts/test_adnetwork_public_export.py"""
import contextlib
import hashlib
import io
import json
import os
import urllib.error
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


# --- Fake urllib response usable both with `with` and without ---
class _FakeResp:
    def __init__(self, status, body_bytes):
        self.status = status
        self._body = body_bytes
    def read(self):
        return self._body
    def getcode(self):
        return self.status
    def __enter__(self):
        return self
    def __exit__(self, *args):
        return False


def _json_resp(status, obj):
    return _FakeResp(status, json.dumps(obj).encode())


class ReadinessCheck(unittest.TestCase):
    OP_SECRET = "OP-SECRET-TOKEN-9876"
    ENV_SECRET = "SECRET-VALUE-1234"
    KEYCHAIN_SECRET = "KEYCHAIN-TOKEN-5678"

    _DEFAULT_VERIFY_OK = {
        "success": True,
        "result": {"id": "tok-id-123", "status": "active"},
        "errors": [], "messages": [],
    }
    _DEFAULT_DETAIL_OK = {
        "success": True,
        "result": {"id": "tok-id-123", "permission_groups": [
            {"name": "R2 Data Catalog: Edit"},
            {"name": "Workers R2 Storage: Edit"},
        ]},
        "errors": [], "messages": [],
    }

    def _probe(self, *, env_token, keychain_rc, root_scripts,
               keychain_token=None, op_rc=1, op_stdout="", op_stderr="",
               verify_status=200, verify_body=None, verify_error_body=None,
               detail_status=200, detail_body=None, detail_error_body=None,
               raise_on_http_error=True):
        """Run check_readiness with mocked subprocess (security/op) and urllib.

        Defaults: keychain_rc==0 with no explicit token -> auto keychain secret
        (new -w contract); op default generic failure; verify/detail default
        active + permissions ok so legacy present-token tests still exit 0.
        """
        env = {} if env_token is None else {"CF_CATALOG_TOKEN": env_token}
        if keychain_token is None:
            keychain_token = self.KEYCHAIN_SECRET if keychain_rc == 0 else ""
        if verify_body is None:
            verify_body = dict(self._DEFAULT_VERIFY_OK)
        if detail_body is None:
            detail_body = dict(self._DEFAULT_DETAIL_OK)

        def fake_run(cmd, *a, **kw):
            proc = mock.MagicMock()
            if cmd and cmd[0] == "security":
                proc.returncode = keychain_rc
                proc.stdout = keychain_token if keychain_rc == 0 else ""
                proc.stderr = "" if keychain_rc == 0 else "could not find generic password"
            elif cmd and cmd[0] == "op":
                proc.returncode = op_rc
                proc.stdout = op_stdout
                proc.stderr = op_stderr
            else:
                proc.returncode = 1
                proc.stdout = ""
                proc.stderr = ""
            return proc

        def fake_urlopen(req, timeout=30):
            url = getattr(req, "full_url", str(req))
            if "/user/tokens/verify" in url:
                if verify_status >= 400 and raise_on_http_error:
                    body = verify_error_body if verify_error_body is not None else verify_body
                    raise urllib.error.HTTPError(
                        url, verify_status, "error", None,
                        io.BytesIO(json.dumps(body).encode()))
                return _json_resp(verify_status, verify_body)
            if "/user/tokens/" in url:
                if detail_status >= 400 and raise_on_http_error:
                    body = detail_error_body if detail_error_body is not None else detail_body
                    raise urllib.error.HTTPError(
                        url, detail_status, "error", None,
                        io.BytesIO(json.dumps(body).encode()))
                return _json_resp(detail_status, detail_body)
            raise AssertionError(f"unexpected urlopen: {url}")

        out, err = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, env, clear=False), \
                mock.patch("adnetwork_public_export.subprocess") as sub, \
                mock.patch("adnetwork_public_export.urllib.request.urlopen", side_effect=fake_urlopen), \
                contextlib.redirect_stdout(out), \
                contextlib.redirect_stderr(err):
            if env_token is None:
                os.environ.pop("CF_CATALOG_TOKEN", None)
            sub.run.side_effect = fake_run
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
            rc, text = self._probe(env_token=self.ENV_SECRET, keychain_rc=1, root_scripts=root)
            self.assertEqual(rc, 0)
            self.assertIn("token=present", text)
            self.assertIn("source=env:CF_CATALOG_TOKEN", text)
            self.assertIn("catalog_read=NOT_ATTEMPTED", text)
            self.assertNotIn(self.ENV_SECRET, text)

    def test_keychain_token_present(self):
        with tempfile.TemporaryDirectory() as d:
            root = _make_candidate(d + "-keychain")
            rc, text = self._probe(env_token=None, keychain_rc=0, root_scripts=root)
            self.assertEqual(rc, 0)
            self.assertIn("source=keychain:service=awai.cf,account=CLOUDFLARE_CATALOG_TOKEN", text)
            self.assertNotIn(self.KEYCHAIN_SECRET, text)

    def test_missing_module_blocks(self):
        with tempfile.TemporaryDirectory() as d:
            root = _make_candidate(d + "-missing", with_module=False)
            rc, text = self._probe(env_token=None, keychain_rc=1, root_scripts=root)
            self.assertEqual(rc, 2)
            self.assertIn("module=missing", text)
            self.assertIn("BLOCKED", text)

    # --- new: 1Password path ---
    def test_1password_success_present_no_leak(self):
        with tempfile.TemporaryDirectory() as d:
            root = _make_candidate(d + "-op-ok")
            rc, text = self._probe(
                env_token=None, keychain_rc=1, root_scripts=root,
                op_rc=0, op_stdout=self.OP_SECRET, op_stderr="")
            self.assertEqual(rc, 0)
            self.assertIn("token=present", text)
            self.assertIn("source=1password:", text)
            self.assertIn("verify_status=active", text)
            self.assertIn("permissions_ok=true", text)
            self.assertNotIn(self.OP_SECRET, text)

    def test_1password_authorization_timeout_verbatim_exit2_blocked(self):
        with tempfile.TemporaryDirectory() as d:
            root = _make_candidate(d + "-op-timeout")
            for stderr in ("error initializing client: authorization timeout",
                           "Authorization Timeout"):
                rc, text = self._probe(
                    env_token=None, keychain_rc=1, root_scripts=root,
                    op_rc=1, op_stdout="", op_stderr=stderr)
                self.assertEqual(rc, 2, text)
                self.assertIn("token=absent", text)
                self.assertIn("source=1password:authorization_timeout", text)
                self.assertIn("catalog_read=BLOCKED", text)
                self.assertIn("module=found", text)
                self.assertIn("authorization_timeout: 承認が必要", text)
                self.assertIn("authorization timeout: 承認が必要", text)
                self.assertIn("OP_SERVICE_ACCOUNT_TOKEN", text)
                self.assertNotIn(self.OP_SECRET, text)

    def test_verify_active_permissions_ok_exit0(self):
        with tempfile.TemporaryDirectory() as d:
            root = _make_candidate(d + "-verify-ok")
            rc, text = self._probe(env_token=self.ENV_SECRET, keychain_rc=1, root_scripts=root)
            self.assertEqual(rc, 0)
            self.assertIn("verify_status=active", text)
            self.assertIn("verify_success=true", text)
            self.assertIn("permissions_ok=true", text)
            self.assertIn("catalog_read=NOT_ATTEMPTED", text)
            self.assertNotIn(self.ENV_SECRET, text)

    def test_verify_disabled_verbatim_api_error_exit2(self):
        with tempfile.TemporaryDirectory() as d:
            root = _make_candidate(d + "-verify-disabled")
            body = {"success": True,
                    "result": {"id": "tok-id-123", "status": "disabled"},
                    "errors": [],
                    "messages": [{"code": 1000, "message": "This API Token is disabled"}]}
            rc, text = self._probe(
                env_token=self.ENV_SECRET, keychain_rc=1, root_scripts=root,
                verify_status=200, verify_body=body)
            self.assertEqual(rc, 2)
            self.assertIn("verify_status=disabled", text)
            self.assertIn("catalog_read=BLOCKED", text)
            self.assertIn("This API Token is disabled", text)
            self.assertIn("1000", text)
            self.assertNotIn(self.ENV_SECRET, text)

    def test_verify_403_verbatim_errors_exit2(self):
        with tempfile.TemporaryDirectory() as d:
            root = _make_candidate(d + "-verify-403")
            body = {"success": False, "result": None,
                    "errors": [{"code": 10000, "message": "Invalid token"}],
                    "messages": []}
            rc, text = self._probe(
                env_token=self.ENV_SECRET, keychain_rc=1, root_scripts=root,
                verify_status=403, verify_body=body, verify_error_body=body)
            self.assertEqual(rc, 2)
            self.assertIn("catalog_read=BLOCKED", text)
            self.assertIn("Invalid token", text)
            self.assertIn("10000", text)
            self.assertNotIn(self.ENV_SECRET, text)

    def test_verify_success_false_exit2(self):
        with tempfile.TemporaryDirectory() as d:
            root = _make_candidate(d + "-verify-false")
            body = {"success": False,
                    "result": {"id": "tok-id-123", "status": "active"},
                    "errors": [{"code": 1001, "message": "Bad token"}],
                    "messages": []}
            rc, text = self._probe(
                env_token=self.ENV_SECRET, keychain_rc=1, root_scripts=root,
                verify_status=200, verify_body=body)
            self.assertEqual(rc, 2)
            self.assertIn("verify_success=false", text)
            self.assertIn("catalog_read=BLOCKED", text)
            self.assertIn("Bad token", text)
            self.assertNotIn(self.ENV_SECRET, text)

    def test_missing_permission_verbatim_error_exit2(self):
        with tempfile.TemporaryDirectory() as d:
            root = _make_candidate(d + "-missing-perm")
            detail = {"success": True,
                      "result": {"id": "tok-id-123", "permission_groups": [
                          {"name": "R2 Data Catalog: Edit"}]},
                      "errors": [], "messages": []}
            rc, text = self._probe(
                env_token=self.ENV_SECRET, keychain_rc=1, root_scripts=root,
                detail_status=200, detail_body=detail)
            self.assertEqual(rc, 2)
            self.assertIn("verify_status=active", text)
            self.assertIn("permissions_missing=", text)
            self.assertIn("Workers R2 Storage: Edit", text)
            self.assertIn("catalog_read=BLOCKED", text)
            self.assertNotIn(self.ENV_SECRET, text)

    def test_detail_fetch_403_verbatim_error_exit2(self):
        with tempfile.TemporaryDirectory() as d:
            root = _make_candidate(d + "-detail-403")
            detail_err = {"success": False, "result": None,
                          "errors": [{"code": 9109, "message": "Missing permission"}],
                          "messages": []}
            rc, text = self._probe(
                env_token=self.ENV_SECRET, keychain_rc=1, root_scripts=root,
                detail_status=403, detail_body=detail_err, detail_error_body=detail_err)
            self.assertEqual(rc, 2)
            self.assertIn("catalog_read=BLOCKED", text)
            self.assertIn("Missing permission", text)
            self.assertIn("9109", text)
            self.assertNotIn(self.ENV_SECRET, text)

    def test_op_binary_missing_distinct_message(self):
        with tempfile.TemporaryDirectory() as d:
            root = _make_candidate(d + "-op-missing")
            out, err = io.StringIO(), io.StringIO()
            with mock.patch.dict(os.environ, {}, clear=False), \
                    mock.patch("adnetwork_public_export.subprocess") as sub, \
                    mock.patch("adnetwork_public_export.urllib.request.urlopen",
                               side_effect=AssertionError("urlopen must not be called")), \
                    contextlib.redirect_stdout(out), \
                    contextlib.redirect_stderr(err):
                os.environ.pop("CF_CATALOG_TOKEN", None)
                # security absent, op binary missing
                def fake_run(cmd, *a, **kw):
                    proc = mock.MagicMock()
                    if cmd and cmd[0] == "security":
                        proc.returncode, proc.stdout, proc.stderr = 1, "", "not found"
                    elif cmd and cmd[0] == "op":
                        raise FileNotFoundError("op")
                    return proc
                sub.run.side_effect = fake_run
                rc = ex.check_readiness(root)
            text = out.getvalue() + err.getvalue()
            self.assertEqual(rc, 2)
            self.assertIn("token=absent", text)
            self.assertIn("BLOCKED", text)
            self.assertIn("op binary missing", text)
            self.assertIn("source=1password:op_missing", text)

    def test_op_item_not_found_distinct_message(self):
        with tempfile.TemporaryDirectory() as d:
            root = _make_candidate(d + "-op-notfound")
            rc, text = self._probe(
                env_token=None, keychain_rc=1, root_scripts=root,
                op_rc=1, op_stdout="", op_stderr="item not found: gftd.cloudflare/API_TOKEN")
            self.assertEqual(rc, 2)
            self.assertIn("item not found", text)
            self.assertIn("catalog_read=BLOCKED", text)
            self.assertNotIn(self.OP_SECRET, text)

    def test_op_vault_access_distinct_message(self):
        with tempfile.TemporaryDirectory() as d:
            root = _make_candidate(d + "-op-vault")
            rc, text = self._probe(
                env_token=None, keychain_rc=1, root_scripts=root,
                op_rc=1, op_stdout="", op_stderr="access denied: vault gftdcojp")
            self.assertEqual(rc, 2)
            self.assertIn("vault access denied", text)
            self.assertIn("catalog_read=BLOCKED", text)

    def test_token_never_appears_in_output(self):
        # Every present-token path must keep the value out of stdout/stderr.
        cases = [
            dict(env_token=self.ENV_SECRET, keychain_rc=1),
            dict(env_token=None, keychain_rc=0),
            dict(env_token=None, keychain_rc=1, op_rc=0, op_stdout=self.OP_SECRET),
        ]
        for i, kw in enumerate(cases):
            with tempfile.TemporaryDirectory() as d:
                root = _make_candidate(f"{d}-noleak-{i}")
                rc, text = self._probe(root_scripts=root, **kw)
                self.assertEqual(rc, 0, text)
                for secret in (self.ENV_SECRET, self.KEYCHAIN_SECRET, self.OP_SECRET):
                    self.assertNotIn(secret, text)


class ExportPathTokenResolution(unittest.TestCase):
    """catalog_rows() must resolve env -> Keychain -w -> 1Password and pass token=."""

    EXPORT_SECRET = "EXPORT-OP-SECRET-4321"

    def _make_export_candidate(self, base):
        d = os.path.join(base, "candidate")
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "datalake_catalog.py"), "w") as f:
            f.write(
                '"""stub export module"""\n'
                'calls = []\n'
                'class _Table:\n'
                '    def current_snapshot(self):\n'
                '        return None\n'
                '    def scan(self):\n'
                '        return self\n'
                '    def to_arrow(self):\n'
                '        return self\n'
                '    def to_pylist(self):\n'
                '        return []\n'
                'class _Catalog:\n'
                '    def load_table(self, name):\n'
                '        return _Table()\n'
                'def connect(token=None):\n'
                '    calls.append(token)\n'
                '    if token is None:\n'
                '        raise AssertionError("token must be passed explicitly")\n'
                '    return _Catalog()\n'
            )
        return d

    def test_catalog_rows_uses_1password_token_via_connect(self):
        with tempfile.TemporaryDirectory() as d:
            root = self._make_export_candidate(d + "-exp-ok")
            seen = []

            class _Table:
                def current_snapshot(self):
                    return None
                def scan(self):
                    return self
                def to_arrow(self):
                    return self
                def to_pylist(self):
                    return []

            class _Catalog:
                def load_table(self, name):
                    return _Table()

            class _Mod:
                def connect(self, token=None):
                    seen.append(token)
                    if token is None:
                        raise AssertionError("token must be passed explicitly")
                    return _Catalog()

            def fake_run(cmd, *a, **kw):
                proc = mock.MagicMock()
                if cmd and cmd[0] == "security":
                    proc.returncode, proc.stdout, proc.stderr = 1, "", "not found"
                elif cmd and cmd[0] == "op":
                    proc.returncode, proc.stdout, proc.stderr = 0, self.EXPORT_SECRET, ""
                return proc
            err = io.StringIO()
            with mock.patch.dict(os.environ, {}, clear=False), \
                    mock.patch("adnetwork_public_export.subprocess") as sub, \
                    mock.patch.object(ex, "_load_catalog_module", return_value=_Mod()), \
                    contextlib.redirect_stderr(err), \
                    contextlib.redirect_stdout(io.StringIO()):
                os.environ.pop("CF_CATALOG_TOKEN", None)
                sub.run.side_effect = fake_run
                rows, snap = ex.catalog_rows(root)
            self.assertEqual((rows, snap), ([], None))
            self.assertEqual(seen, [self.EXPORT_SECRET])
            self.assertNotIn(self.EXPORT_SECRET, err.getvalue())

    def test_catalog_rows_timeout_same_message_exit2(self):
        with tempfile.TemporaryDirectory() as d:
            root = self._make_export_candidate(d + "-exp-timeout")
            def fake_run(cmd, *a, **kw):
                proc = mock.MagicMock()
                if cmd and cmd[0] == "security":
                    proc.returncode, proc.stdout, proc.stderr = 1, "", "not found"
                elif cmd and cmd[0] == "op":
                    proc.returncode, proc.stdout, proc.stderr = (
                        1, "", "error initializing client: authorization timeout")
                return proc
            err = io.StringIO()
            with mock.patch.dict(os.environ, {}, clear=False), \
                    mock.patch("adnetwork_public_export.subprocess") as sub, \
                    contextlib.redirect_stderr(err), \
                    contextlib.redirect_stdout(io.StringIO()):
                os.environ.pop("CF_CATALOG_TOKEN", None)
                sub.run.side_effect = fake_run
                with self.assertRaises(SystemExit) as cm:
                    ex.catalog_rows(root)
            self.assertEqual(cm.exception.code, 2)
            text = err.getvalue()
            self.assertIn("source=1password:authorization_timeout", text)
            self.assertIn("catalog_read=BLOCKED", text)
            self.assertIn("authorization_timeout: 承認が必要", text)
            self.assertIn("authorization timeout: 承認が必要", text)


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
