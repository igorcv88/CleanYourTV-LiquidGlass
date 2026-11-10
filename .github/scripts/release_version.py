"""Derive the APK version, Android versionCode and GitHub tag for a release.

The run number lives in SemVer's numeric core, rather than a dotted prerelease
suffix. This avoids GitHub sorting beta.9 above beta.13 as a text comparison.
"""

import sys


def release_identity(channel: str, run_number: int) -> tuple[str, int, str]:
    if channel not in {"beta", "stable"}:
        raise ValueError("channel must be beta or stable")
    if not isinstance(run_number, int) or isinstance(run_number, bool) or run_number < 1:
        raise ValueError("run number must be a positive integer")

    version = f"3.3.{run_number}"
    if channel == "beta":
        version += "-beta"
    return version, 330000 + run_number, f"v{version}"


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: release_version.py {beta|stable} RUN_NUMBER", file=sys.stderr)
        return 2
    try:
        if not sys.argv[2].isascii() or not sys.argv[2].isdecimal():
            raise ValueError("run number must contain ASCII decimal digits")
        version, version_code, tag = release_identity(sys.argv[1], int(sys.argv[2]))
    except ValueError as error:
        print(error, file=sys.stderr)
        return 2
    print(f"{version}\t{version_code}\t{tag}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
