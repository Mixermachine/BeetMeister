# Firmware Versioning

The firmware version string is embedded in the app binary as a TLV metadata entry.
At runtime, `beet_maintenance_get_info()` parses it and exposes it via BLE.

## Hard constraint

`BEET_MAINTENANCE_FIRMWARE_VERSION_MAX_LEN` is frozen at **32 characters**.
All version strings must fit within 33 bytes (32 + null).

## Version string sources

The CMake build system selects the version string based on git state:

| Tree state | Source | Example | Len |
|-----------|--------|---------|-----|
| Clean, tag exists | `git describe --tags --abbrev=0` | `v0.0.0-ci-20260628-1` | 24 |
| Clean, no tag | `v0.0.0-<short-hash>` | `v0.0.0-0452c52` | 16 |
| Dirty | `dev-<short-hash>` | `dev-0452c52` | 11 |
| No git | `v0.0.0-unknown` | fallback | 15 |

`git describe --tags --dirty` is **never** used — it produces strings too long
for the 32-char buffer.

## Tag format

```
vX.Y.Z-<pipeline>-<YYYYMMDD>-<N>
```

- `vX.Y.Z`: semver (placeholder `v0.0.0` before first release)
- `<pipeline>`: `ci` only (release builds use pure semver tags like `v1.2.3`)
- `<YYYYMMDD>`: date tag was created
- `<N>`: sequential build number on that date

Examples:
- `v0.0.0-ci-20260628-1` (24 chars) — CI build from clean tree
- `v1.2.3` (6 chars) — release

The pipeline name `ci` replaced the earlier `ci-verify` prefix (deprecated,
7 chars saved). No other pipeline names are used.

Max realistic tag: `v9.9.9-ci-99991231-999` = 26 chars. Well within 32.

## CI policy

- Tag before build: `git tag v0.0.0-ci-$(date +%Y%m%d)-$N`
- Tree must be clean: `git diff-index --quiet HEAD --`
- Assert tag length ≤ 32 before proceeding
- Tag must point at the exact commit being built

## Dev builds (dirty workspace)

Dirty trees automatically receive `dev-<7-char-hash>`. This is always traceable
(`git log dev-0452c52`) and always short (11 chars). Dev builds use
`image_kind=bundled` and are not eligible for OTA updates.

## How the version reaches the firmware

`beet_firmware/CMakeLists.txt` selects the version string and passes it to
`gen_beet_metadata_header.py`, which embeds it as a TLV entry in the firmware
image's `.rodata` section. At runtime, `beet_maintenance.c:beet_copy_tlv_string()`
copies it into the `beet_maintenance_info_t` struct. If the TLV string exceeds
32 chars, the parser truncates it with a warning — the controller still boots
and exposes a truncated version via BLE.
