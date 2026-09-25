#!/usr/bin/env python3
"""Create the verifier for RELAY_HTTP_AUTH_BASIC_PASSWORD_HASH without echoing the password."""
import base64
import getpass
import hashlib
import secrets


def main() -> None:
    password = getpass.getpass("Management password: ")
    if len(password) < 12:
        raise SystemExit("Use a password of at least 12 characters.")
    if password != getpass.getpass("Repeat password: "):
        raise SystemExit("Passwords differ.")
    salt = secrets.token_bytes(16)
    key = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 600_000, dklen=32)
    print("pbkdf2-sha256$600000$" + base64.b64encode(salt).decode() + "$" + base64.b64encode(key).decode())


if __name__ == "__main__":
    main()
