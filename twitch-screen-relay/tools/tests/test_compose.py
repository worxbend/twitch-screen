"""Unit checks for guarded Compose migration, without running a deployment."""
import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("guarded_compose", Path(__file__).parents[1] / "compose.py")
assert spec is not None and spec.loader is not None
compose = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compose)


class ComposeGuardTests(unittest.TestCase):
    def test_interpolation_and_rendered_environment_are_both_checked(self):
        keys = compose.obsolete_keys("RELAY_HTTP_AUTH_UNKNOWN=synthetic\nRELAY_HTTP_AUTH_EMPTY=\n",
                                     {"services": {"relay": {"environment": {
                                         "RELAY_HTTP_AUTH_API_TOKEN": "synthetic",
                                         "RELAY_HTTP_AUTH_BASIC_USERNAME": "   ",
                                         "RELAY_HTTP_AUTH_NONE": None,
                                         "RELAY_HTTP_AUTH_EMPTY": "",
                                         "OTHER_KEY": "synthetic"}}}})
        self.assertEqual(keys, ["RELAY_HTTP_AUTH_API_TOKEN", "RELAY_HTTP_AUTH_BASIC_USERNAME", "RELAY_HTTP_AUTH_UNKNOWN"])

    @patch.object(compose.subprocess, "run")
    @patch("sys.stderr")
    def test_obsolete_input_never_executes_requested_command_or_prints_value(self, stderr, run):
        run.side_effect = [subprocess.CompletedProcess([], 0, "RELAY_HTTP_AUTH_UNKNOWN=secret-synthetic\n", ""),
                           subprocess.CompletedProcess([], 0, '{"services":{}}', "")]
        self.assertEqual(compose.main(["--env-file", "synthetic.env", "-p", "synthetic", "up", "-d"]), 2)
        self.assertEqual(run.call_count, 2)
        output = "".join(str(call.args[0]) for call in stderr.write.call_args_list)
        self.assertIn("RELAY_HTTP_AUTH_UNKNOWN", output)
        self.assertNotIn("secret-synthetic", output)

    @patch.object(compose.subprocess, "run")
    @patch("sys.stderr")
    def test_preflight_errors_are_fail_closed_and_redacted(self, stderr, run):
        run.side_effect = subprocess.CalledProcessError(1, [], stderr="private-synthetic-value")
        self.assertEqual(compose.main(["up", "-d"]), 2)
        self.assertEqual(run.call_count, 1)
        output = "".join(str(call.args[0]) for call in stderr.write.call_args_list)
        self.assertNotIn("private-synthetic-value", output)

    @patch.object(compose.subprocess, "run")
    def test_clean_preflight_preserves_global_options_and_command_exit(self, run):
        run.side_effect = [subprocess.CompletedProcess([], 0, "", ""),
                           subprocess.CompletedProcess([], 0, '{"services":{}}', ""),
                           subprocess.CompletedProcess([], 7)]
        arguments = ["--env-file", "first.env", "--env-file", "second.env", "-p", "synthetic",
                     "-f", "compose.yaml", "--project-directory", "/synthetic", "up", "--build", "-d"]
        self.assertEqual(compose.main(arguments), 7)
        command = run.call_args.args[0]
        self.assertEqual(command, ["docker", "compose", "--env-file", "first.env", "--env-file", "second.env",
                                   "--file", "compose.yaml", "--project-name", "synthetic",
                                   "--project-directory", "/synthetic", "up", "--build", "-d"])


if __name__ == "__main__":
    unittest.main()
