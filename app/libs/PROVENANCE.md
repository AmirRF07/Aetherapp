# Provenance of the binaries in `app/libs`

Written for audit finding **F-8 (1.3.0)**.

## `psiphontunnel-2.0.39.aar`

| | |
|---|---|
| Size | 44 395 730 bytes |
| SHA-256 of the **committed file** | `7bcbb09ed53ac44f858298253eeb77c1baf7fefd105a8d1ef4eee3a148dd7ac1` |
| Upstream project | Psiphon `psiphon-tunnel-core`, Android library (`PsiphonTunnel` / `ca.psiphon`) |
| Upstream source | <https://github.com/Psiphon-Labs/psiphon-tunnel-core> |
| Upstream Android build | <https://github.com/Psiphon-Labs/psiphon-tunnel-core/tree/master/MobileLibrary/Android> |

### What that hash does and does not prove

It proves the file in this repository has not changed since it was committed —
the same hash is in `SOURCE_MANIFEST.sha256`, so any modification breaks the
tree verification.

It proves **nothing about where the file came from.** It is a hash *of the
committed artefact*, not of a published upstream release artefact. That gap is
the finding: a reviewer cannot currently confirm that this 44 MB binary is the
untampered output of the upstream project.

### Closing the gap — not yet done

The audit environment had no network access, so the upstream published checksum
could not be retrieved and compared. Whoever does this next needs to either:

1. **Compare against upstream.** Download the corresponding
   `psiphontunnel-2.0.39.aar` (or the release it was cut from) from the upstream
   release page, hash it, and record *that* hash here next to the URL it came
   from. If the two hashes differ, the committed binary must be replaced, not
   explained.
2. **Or build it in CI, which is the better answer.** The workflow already
   installs Go (`actions/setup-go`) for the lyrebird pluggable transport, so the
   toolchain for `MobileLibrary/Android/make.bash` is present. Building the AAR
   from a pinned upstream commit removes the binary from the repository entirely
   and replaces "trust this file" with "here is the commit it was built from".

Until one of those is done, treat the table above as an inventory entry, not as a
verification.
