# Cleaner TV Liquid Glass — signed release toolkit

**Use the complete Setup ZIP** when you want both the Home launcher and all original optimization tools.
The separate APK installs only the launcher: it does **not** debloat the TV.

## What is included

- `CleanYourTV-Scenic.apk`: one native Android TV launcher, the complete v3.3 Home engine and 17 offline-selectable themes;
- `launcher/build-and-install.sh` and `.ps1`: upstream Home installer with support for a signed `PREBUILT_APK`;
- `tools/`, `examples/`, `addons/`, `themes/`, `docs/`: upstream v3.3 optimization scripts, profiles, add-ons, theme assets and documentation;
- `installer/setup.sh` / `setup.ps1`: guided prebuilt-APK installation that **does not disable stock Home**.
- `CleanYourTV-Setup.exe`: double-click Windows launcher for the PowerShell installer, with saved TV selection;
- `installer/interactive.ps1` and `devices.ps1`: readable Windows device catalog and IP management.

## GitHub Releases (repository owner)

The publication workflow is `.github/workflows/release.yml` (a separate `pr-ci.yml` validates pull requests).
Publication runs **only** when the owner chooses **Actions → Cleaner TV Liquid Glass — Publish full Setup release → Run workflow**.
That invocation validates the tools and builds/signs the Android launcher as an internal step,
then assembles the complete Setup ZIP with the APK inside. The GitHub Release receives four downloads:
the **Setup ZIP**, its `.sha256`, the optional standalone APK, and its `.sha256`.
PR validation builds do not publish releases or upload files to the Actions artifacts store.
The ZIP now includes a small native Windows `.exe` entrypoint; its PowerShell and Python logic remains inspectable in the ZIP.

## Installation (read first)

1. Extract the full ZIP on the computer. Use a computer connected to the same network as the TV.
2. Enable ADB debugging on the TV and approve its debugging prompt. Windows requires Python 3.10+;
   the installer automatically prepares `tools\venv` in each extracted ZIP, so all included scripts and
   optional add-ons can use the same interpreter. The ADB key is reused between releases.
3. **Windows:** double-click `CleanYourTV-Setup.exe` in the extracted ZIP. On first run, enter the TV's IP address.
   Later runs offer the saved TV by default, or allow adding a second TV or changing an IP.
   Advanced manual use is still supported: `./installer/setup.ps1 -TvHost <TV_IP>` in PowerShell.
   **Linux/macOS:** use `TV_HOST=<TV_IP> bash installer/setup.sh`.
4. The installer records the original Home, installs the signed APK, compiles it for speed and attempts
   the Android Home handover **while leaving the original Home enabled**. If the firmware refuses to change
   Home without disabling stock, stop and inspect the model documentation before taking further action.
5. In the Home settings, open **Aparência**, use the remote's arrows, and press OK to select among all 17 themes.

### Persistent Windows settings

- `%LOCALAPPDATA%\CleanYourTV\devices.json` stores TV names, addresses and the default choice. It is saved only
  after a successful installation. The next Setup ZIP reads the same file; do not copy it into a public issue.
- `tools\venv` belongs to each extracted Setup ZIP. It is created automatically and is intentionally not
  stored in `%LOCALAPPDATA%`: other Windows scripts and add-ons rely on this relative location.
- `%LOCALAPPDATA%\CleanYourTV\adbkey` stores the persistent ADB private/public key pair (a sensitive
  credential). Keep this directory private.
- `%LOCALAPPDATA%\CleanYourTV\recovery` keeps dated copies of the original Home and disabled-package list.
- When moving from an old ZIP that stored its key under `tools/adbkey`, you may need to approve ADB once more;
  subsequent installations reuse the new persistent key.
- No administrator privileges or permanent environment variables are required. The installer sets `TV_HOST`
  only for the process that connects to the selected TV.

## Optimizations, firmware safety and rollback

The CleanYourTV 3.3 toolkit contains model-specific debloat profiles and optional add-ons.
**Installing the APK is not equivalent to running those tools.** On Linux/macOS the guide offers debloat
only if exactly one profile in `examples/*/debloat.txt` matches the TV's complete firmware fingerprint;
even then it requires typing `OPTIMIZAR`. Windows leaves debloat for a separate explicit action.

- Unknown Philips firmware: **no debloat should run** until its profile is individually verified.
- Protect Philips Ambilight, Chromecast, HDMI/CEC, Live TV, system settings, updates and stock Home.
- Use `tools/restore.sh` / `tools/restore.ps1` with the same list to undo disabled packages. Keep the
  `recovery/` folder and follow upstream recovery instructions.
- Themes keep the previous external configuration in `Android/data/com.gathin.tvhome/files/theme-picker-backup/`.
- If you already installed a debug-signed launcher using the same package name, this signed APK may
  fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. **Do not uninstall the working Home blindly**;
  check its signature and follow an explicit rollback procedure first.
- Disable ADB debugging when done testing.

No performance figure or visual fidelity can be guaranteed without testing on the exact TV.
