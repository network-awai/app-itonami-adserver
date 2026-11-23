"""python3 scripts/test_run_export_with_1password.py

Offline tests for scripts/run_export_with_1password.sh. All `security`/`op`
binaries are PATH stubs in a temp dir with canned rc/stdout/stderr; no real
Keychain or 1Password access, no network. Fake secrets are distinctive
strings that must never appear in helper stdout/stderr; the fake child
prints only presence/length, never a value.
"""
import os
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
HELPER = os.path.join(HERE, "run_export_with_1password.sh")
OP_REFERENCE = "op://gftdcojp/gftd.cloudflare/API_TOKEN"
FAKE_SA = "fake-sa-token-0000000001"
FAKE_CF = "fake-catalog-token-00000001"
PRESET_SA = "preset-sa"          # len 8
PRESET_CF = "preset-cf-token"    # len 15


def _shq(s):
    return "'" + s.replace("'", "'\"'\"'") + "'"


def _write_stub(path, stdout="", stderr="", rc=0, log=None):
    lines = ["#!/bin/sh"]
    if log:
        lines.append("printf '%s\\n' \"$@\" > " + _shq(log))
    if stdout:
        lines.append("printf '%s' " + _shq(stdout))
    if stderr:
        lines.append("printf '%s' " + _shq(stderr) + " >&2")
    lines.append("exit %d" % rc)
    with open(path, "w") as f:
        f.write("\n".join(lines) + "\n")
    os.chmod(path, 0o755)


def _write_child(path):
    body = (
        "#!/bin/sh\n"
        "if [ -n \"${OP_SERVICE_ACCOUNT_TOKEN:-}\" ]; then echo SA_PRESENT=1; "
        "else echo SA_PRESENT=0; fi\n"
        "if [ -n \"${CF_CATALOG_TOKEN:-}\" ]; then echo CF_PRESENT=1; "
        "else echo CF_PRESENT=0; fi\n"
        "echo SA_LEN=${#OP_SERVICE_ACCOUNT_TOKEN}\n"
        "echo CF_LEN=${#CF_CATALOG_TOKEN}\n"
    )
    with open(path, "w") as f:
        f.write(body)
    os.chmod(path, 0o755)


class HelperCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.bin = os.path.join(self._tmp.name, "bin")
        os.makedirs(self.bin)
        self.child = os.path.join(self._tmp.name, "fake-child")
        _write_child(self.child)
        self.sec_log = os.path.join(self._tmp.name, "security.args")
        self.op_log = os.path.join(self._tmp.name, "op.args")

    def base_env(self, **extra):
        env = dict(os.environ)
        env.pop("OP_SERVICE_ACCOUNT_TOKEN", None)
        env.pop("CF_CATALOG_TOKEN", None)
        env["PATH"] = self.bin + os.pathsep + "/usr/bin" + os.pathsep + "/bin"
        env.update(extra)
        return env

    def stubs(self, sec=(1, "", ""), op=(1, "", ""), with_op=True):
        _write_stub(os.path.join(self.bin, "security"),
                    stdout=sec[1], stderr=sec[2], rc=sec[0], log=self.sec_log)
        if with_op:
            _write_stub(os.path.join(self.bin, "op"),
                        stdout=op[1], stderr=op[2], rc=op[0], log=self.op_log)

    def run_helper(self, args, env):
        return subprocess.run(
            ["bash", HELPER] + args,
            env=env, capture_output=True, text=True, timeout=15,
        )

    def run_child(self, args, env):
        return subprocess.run(
            ["bash", HELPER, "--", self.child] + args,
            env=env, capture_output=True, text=True, timeout=15,
        )

    def assert_no_leak(self, proc, *secrets):
        blob = proc.stdout + proc.stderr
        with open(HELPER) as _f:
            cur = _f.read()
        self.assertNotIn("set -x", cur)
        for secret in secrets:
            if secret:
                self.assertNotIn(secret, blob)
                self.assertNotIn(secret, proc.stdout)
                self.assertNotIn(secret, proc.stderr)


class TestHelper(HelperCase):
    def test_a_keychain_sa_loaded_child_sees_it_no_leak(self):
        self.stubs(sec=(0, FAKE_SA, ""), op=(0, FAKE_CF, ""))
        proc = self.run_child([], self.base_env())
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("SA_PRESENT=1", proc.stdout)
        self.assertIn("CF_PRESENT=1", proc.stdout)
        self.assertIn("SA_LEN=%d" % len(FAKE_SA), proc.stdout)
        self.assertIn("CF_LEN=%d" % len(FAKE_CF), proc.stdout)
        self.assert_no_leak(proc, FAKE_SA, FAKE_CF)
        # Exact selectors were used.
        with open(self.sec_log) as f:
            self.assertEqual(
                " ".join(f.read().splitlines()),
                "find-generic-password -s awai.cf -a OP_SERVICE_ACCOUNT_TOKEN -w")
        with open(self.op_log) as f:
            self.assertEqual(" ".join(f.read().splitlines()), "read " + OP_REFERENCE)

    def test_b_second_child_sees_absent_after_helper(self):
        self.stubs(sec=(0, FAKE_SA, ""), op=(0, FAKE_CF, ""))
        env = self.base_env()
        first = self.run_child([], env)
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertIn("SA_PRESENT=1", first.stdout)
        # Second child from the same (never-mutated) parent env sees neither
        # injected var: the helper is a subprocess whose exports cannot
        # persist in the invoking shell, and its trap unsets on exit paths.
        second = subprocess.run([self.child], env=env,
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(second.returncode, 0)
        self.assertIn("SA_PRESENT=0", second.stdout)
        self.assertIn("CF_PRESENT=0", second.stdout)
        self.assertIn("SA_LEN=0", second.stdout)
        self.assertIn("CF_LEN=0", second.stdout)
        self.assert_no_leak(second, FAKE_SA, FAKE_CF)

    def test_c_authorization_timeout_verbatim_exit2_no_leak(self):
        self.stubs(sec=(1, "", "security: could not find item"),
                   op=(1, "", "error initializing client: authorization timeout"))
        proc = self.run_child([], self.base_env())
        self.assertEqual(proc.returncode, 2)
        self.assertIn("authorization timeout: 承認が必要", proc.stderr)
        self.assertIn("OP_SERVICE_ACCOUNT_TOKEN", proc.stderr)
        self.assertNotIn("SA_PRESENT", proc.stdout)
        self.assert_no_leak(proc, FAKE_SA, FAKE_CF)

    def test_d_presets_not_overridden_and_stubs_not_called(self):
        self.stubs(sec=(0, FAKE_SA, ""), op=(0, FAKE_CF, ""))
        env = self.base_env(OP_SERVICE_ACCOUNT_TOKEN=PRESET_SA,
                            CF_CATALOG_TOKEN=PRESET_CF)
        proc = self.run_child([], env)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("SA_LEN=%d" % len(PRESET_SA), proc.stdout)
        self.assertIn("CF_LEN=%d" % len(PRESET_CF), proc.stdout)
        self.assertNotIn("SA_LEN=%d" % len(FAKE_SA), proc.stdout)
        self.assertNotIn("CF_LEN=%d" % len(FAKE_CF), proc.stdout)
        self.assertFalse(os.path.exists(self.sec_log))
        self.assertFalse(os.path.exists(self.op_log))
        self.assert_no_leak(proc, FAKE_SA, FAKE_CF, PRESET_SA, PRESET_CF)

    def test_no_args_usage_exit2(self):
        self.stubs()
        proc = self.run_helper([], self.base_env())
        self.assertEqual(proc.returncode, 2)
        self.assertIn("usage:", proc.stderr)

    def test_security_absent_op_still_yields_catalog_token(self):
        self.stubs(sec=(1, "", ""), op=(0, FAKE_CF, ""))
        proc = self.run_child([], self.base_env())
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("SA_PRESENT=0", proc.stdout)
        self.assertIn("CF_PRESENT=1", proc.stdout)
        self.assert_no_leak(proc, FAKE_SA, FAKE_CF)

    def test_op_binary_missing_distinct(self):
        self.stubs(sec=(1, "", ""), with_op=False)
        proc = self.run_child([], self.base_env())
        self.assertEqual(proc.returncode, 2)
        self.assertIn("op binary missing", proc.stderr)
        self.assertNotIn("authorization timeout: 承認が必要", proc.stderr)

    def test_op_item_not_found_and_vault_denied_distinct(self):
        for op_err, needle in [
            ("[ERROR] item not found: op://gftdcojp/gftd.cloudflare/API_TOKEN", "item not found"),
            ("vault access denied: no access to vault gftdcojp", "vault access denied"),
        ]:
            with self.subTest(op_err=op_err):
                self.stubs(sec=(1, "", ""), op=(1, "", op_err))
                proc = self.run_child([], self.base_env())
                self.assertEqual(proc.returncode, 2)
                self.assertIn(needle, proc.stderr)
                self.assert_no_leak(proc, FAKE_SA, FAKE_CF)

    def test_implicit_check_forwards_to_python3_exporter(self):
        self.stubs(sec=(0, FAKE_SA, ""), op=(0, FAKE_CF, ""))
        py_log = os.path.join(self._tmp.name, "python3.args")
        _write_stub(os.path.join(self.bin, "python3"),
                    stdout="PY_ARGS_OK\n", rc=0, log=py_log)
        proc = self.run_helper(["--check"], self.base_env())
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("PY_ARGS_OK", proc.stdout)
        with open(py_log) as f:
            args = f.read().strip().splitlines()
        self.assertTrue(args[0].endswith("adnetwork_public_export.py"), args)
        self.assertEqual(args[1:], ["--check"])
        self.assert_no_leak(proc, FAKE_SA, FAKE_CF)

    def test_static_trap_and_no_trace_patterns(self):
        with open(HELPER) as _f:
            cur = _f.read()
        self.assertIn("trap cleanup EXIT", cur)
        self.assertIn("unset OP_SERVICE_ACCOUNT_TOKEN", cur)
        self.assertIn("unset CF_CATALOG_TOKEN", cur)
        self.assertNotIn("set -x", cur)
        self.assertNotIn('echo "$OP_SERVICE_ACCOUNT_TOKEN"', cur)
        self.assertNotIn('echo "$CF_CATALOG_TOKEN"', cur)


if __name__ == "__main__":
    unittest.main()
