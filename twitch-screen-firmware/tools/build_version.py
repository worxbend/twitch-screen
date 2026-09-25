"""Embed the source commit, within HELLO's 15-byte content width."""
import subprocess

Import("env")

try:
    version = subprocess.check_output(
        ["git", "rev-parse", "--short=10", "HEAD"], cwd=env.subst("$PROJECT_DIR"), text=True
    ).strip()
except (OSError, subprocess.CalledProcessError):
    version = "source"
env.Append(CPPDEFINES=[("FIRMWARE_VERSION", env.StringifyMacro(version))])
