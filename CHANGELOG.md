# Changelog

## 1.2.9-r3 — security remediation + the connected mark

Version unchanged (1.2.9 / versionCode 13) and signed with the same certificate,
so this installs over an existing 1.2.9 without uninstalling.

**Security (full report: `docs/SECURITY_AUDIT_1.2.9-r3.md`, score 79 -> 93)**

- The on-disk diagnostics log is now encrypted with a hardware-backed AES-256-GCM
  key; a plaintext log from an older build is read once, then shredded.
- The engine's identity files (WireGuard private key + WARP device) are sealed at
  rest and only exist in the clear while the tunnel is actually running.
- LAN sharing is no longer an open proxy: remote clients must come from a local
  address AND authenticate (SOCKS5 user/pass or HTTP Basic) with a generated
  16-character password shown in the Share card. Loopback is unchanged.
- TLS trust anchors are restricted to system CAs, so a user-installed root
  certificate can no longer intercept the app's own requests.
- The app verifies its own signing certificate against the published fingerprint
  and shows it, with the APK's SHA-256, in About; CI publishes both per artifact.
- New "Copy logs, addresses removed" button for logs that get pasted in public.
- CI runs the unit tests before building a release (16 new tests).

**UI**

- Connected no longer shows a generic tick: it draws Aether's own A - the launcher
  icon's letterform - lit, colour-cycling, with a diagonal light sweep, an internal
  scan bar over a hairline grid, and a one-shot reveal wipe when the tunnel comes
  up. Composed only while connected.

# 1.2.9-r2 — crash on connect fixed + security audit (version unchanged)

Version stays `1.2.9` / code 13; `PATCHLEVEL` unchanged. No dependency, permission,
manifest or native change.

## The crash (`IndexOutOfBoundsException: No group 1`)

- **Root cause: `AiRedaction.redactLine` asked for a capture group the IPv6 pattern
  does not have.** `IPV6` is written entirely with non-capturing `(?:…)` groups, so
  `m.groupValues[1]` threw on the first log line containing any IPv6 literal. `IPV4`
  on the next line happens to have one capture group, which is why the same idiom
  worked there and hid the asymmetry. The engine prints
  `[+] identity ready: … ipv6=2606:…` on every connect, so the redactor threw on
  every session, in both plain and chained mode.
- **Why the app closed a minute or two after connecting:** `MainActivity` runs the
  on-connect analysis at the end of the *connected* branch, after the tunnel
  self-test and after an exit-IP probe that waits up to 100 s. The digest is built
  inside `scope.launch`, and `SupervisorJob` does not catch an unhandled throw — it
  reached the thread's default handler and killed the process, leaving the
  `last_crash.txt` the crash screen showed on the next launch.
- **Fix:** both address rules mask `m.value` (group 0, which always exists), so the
  code no longer depends on how a pattern is bracketed. Labelled rules read their
  label with `groupValues.getOrElse(1)`.
- **Containment, in three layers:** `digest` redacts through `safeRedactLine`, which
  drops a line that throws (never falls back to its raw text); `analyze` builds the
  digest inside `runCatching` and reports an ordinary failure; and `AiSession.scope`
  now carries a `CoroutineExceptionHandler`, so no AI failure can ever take the
  tunnel down again — it is logged, the `Running` state is resolved so the UI cannot
  hang on a spinner, and the app keeps running.

## Two leaks found while fixing it

- **IPv4-mapped IPv6 was half-masked.** The general rules matched `::ffff:203` and
  stopped, leaving `.0.113.9` in the digest — three octets of the public address the
  rule exists to hide. Mapped forms (`::ffff:1.2.3.4`, `0:0:0:0:0:ffff:1.2.3.4`,
  `::1.2.3.4`) are now matched whole and masked with the IPv4 policy, private and
  loopback quads still kept intact.
- **JSON-shaped identifiers were not recognised.** Half of this app's log is
  Psiphon's JSON notices: `sessionId=` matched, `"sessionId":"5a4c…"` did not, so the
  Psiphon session id travelled to the model verbatim. Both the credential rule and
  the identifier rule now accept the quoted form.

## Tests

- `AiRedactionTest` grew from 8 to 12 cases: every IPv6 text form (full, compressed,
  `::`, `::1`, link-local, bracketed, IPv4-mapped), JSON-shaped identifiers and
  credentials, and a realistic mixed session log through `digest` asserting both
  halves — nothing identifying survives, and the timestamps, loopback address, Rust
  module paths and build stamp do.
- Every assertion in that file would have failed before this fix, which means the
  suite was never run against the shipped build. `gradle :app:testReleaseUnitTest`
  should be a CI step; see `docs/SECURITY_AUDIT_1.2.9.md` §7.

## Security audit

- Full mobile-app security audit of the shipped tree: `docs/SECURITY_AUDIT_1.2.9.md`.
  **Weighted score 79/100**, summarised in both READMEs, with the AI-privacy
  statement (what the digest contains, and what is never sent) stated explicitly.
  The committed release signing key (`.github/ci-keystore.jks.b64`) remains the one
  critical finding and still needs a key rotation, not a code change.

# 1.2.9 — AI path fixes (version unchanged)

Version stays `1.2.9` / code 13; `PATCHLEVEL` unchanged.

## Errors (a2, a4, a6)

- **a2 was misdiagnosed by the app.** `Internal error encountered` is Google's own
  HTTP 500. `GeminiClient` mapped every `>= 500` to `TRANSPORT` and told the user
  "could not reach Google through the tunnel" — about a request that reached Google
  and came back. That is why "try again" always worked. New `AiErrorKind.SERVER_ERROR`
  says whose fault it is, and 5xx/429/socket failures are now retried
  automatically (3 attempts, exponential, capped at 6s), so the user usually never
  sees it.
- **a4 (429) now honours Google's own `retryDelay`.** The field log has five 429s
  in ten seconds because nothing read `"Please retry in 2.379075806s"`, and there
  was no backoff at all. Both `Retry-After` and `error.details[].retryDelay` are
  parsed and obeyed.
- **a3/a6 root cause: thinking tokens, not a parse bug.** `maxOutputTokens` is a
  budget for *everything* the model emits, and on a thinking model the reasoning
  is spent first. The advisor asked for 1400 tokens with a 12,000-char log digest
  attached; the model spent the budget thinking and returned an empty or truncated
  body — logged as `200` + `advisor answer could not be parsed as JSON`. Fixed by
  `thinkingConfig.thinkingBudget = 0`, `responseMimeType: application/json`,
  budgets raised to 3072/5120, skipping `thought: true` parts, and detecting
  `finishReason: MAX_TOKENS` as its own error kind with one larger retry.
- **Every error string is translated.** `gate.name`, `"unreadable answer"` and
  `"empty log"` reached the screen as raw English. Causes now travel on the state
  (`AiMessage.errorKind/gate`, `AiProbe.Failed.gate`, `AiAdviceState.Failed.kind/reason`)
  and one function, `aiFailureText`, owns the wording. Google's English boilerplate
  is no longer appended when the translated headline already says it.

## Models

- New `AiModelPolicy`: a five-model allow-list, applied on **all four** paths —
  fresh discovery, the cached list replayed at startup, the default pick, and the
  id sent to `generateContent`. Filtering only on discovery is why a refresh (or a
  cache from 1.2.8) put image/video/Pro models back.
- Ordered newest→oldest by rank, not by string sort (lexically `3.1` precedes
  `3.5` and `flash-lite-latest` sorts after every numbered release).
- Picker rows are numbered `1. gemini-3.8-flash`, from the model's fixed position
  in the allow-list rather than its index in the visible list.
- Discovery succeeding with none of the five available now says so, instead of
  leaving an empty picker to refresh forever.

## UI and chat

- **AI icon coverage is now complete**: `AiTopic.RECONNECT_SECS` existed with
  nothing pointing at it, and three unrelated tuning fields shared one icon
  labelled "TLS groups". Split into one block per field. Icons added to the About
  row and the split-tunnel app picker. Zero topics are now unanchored.
- **"Applies on the next connection" is a dialog.** It was 12sp dimmed text inside
  the card, directly above Apply — so pressing Apply scrolled it out of view in
  the chat and it sat below the fold in the advisor. The single most important
  sentence in the feature was the least visible thing on screen.
- **Retry icon on failed sends**, resending the original prompt (kept on the
  bubble as `sourcePrompt`, not guessed by walking backwards — after an edit or a
  delete the bubble above a failure is not necessarily its cause).
- **Edit and delete messages**, single and in bulk: long-press to start a
  selection, select-all, confirmed bulk delete. Editing truncates the conversation
  after that point and re-asks.
- **"Did not understand? Ask the assistant"** on every explanation sheet. Carries
  the option name, its current value, the app's own description and the
  explanation the user just failed to follow into the chat, phrased in their
  language, and asks for a simpler answer.

## Security (audit of the AI path)

- **The advisor was sending the user's WARP identity to Google.** `AiRedaction`
  masked IPv4 and nothing else, so the `identity ready:` line the engine writes on
  every connect left with `device=<uuid>` and a full, globally routable, stable
  IPv6 intact. Masking v4 to a /16 while shipping a /128 is not partial protection.
  IPv6 is now masked to its /32, and `device=`/`sessionId`/bare UUIDs are removed.
  Verified against all 312 lines of the supplied field log.
- v1 (JAR) signing turned **off**: `minSdk` is 26, so it was unused on every
  supported device and is the scheme Janus-class attacks target. Certificate
  unchanged, so in-place updates still install.
- The committed public release key (`.github/ci-keystore.jks.b64`) is **no longer
  used automatically** — it now needs `-PaetherAllowPublicCiKey=true`. CI is
  unaffected. The file stays until the key is rotated; deleting it would break
  updates for every existing user. Still the project's highest-severity issue.
- `android:usesCleartextTraffic="false"` stated explicitly in the manifest.
- First unit tests in the project, covering the two pure-Kotlin pieces above.

## Install warning (a1)

Not fixed, and not fixable in the build. See `docs/PLAY_PROTECT.md`.
WhiteAestherMobile does not avoid this dialog — its own README documents it as
expected for a sideloaded APK, and its Play submission has the required in-app
VPN disclosure still open as a blocking item. The `a1` prompt is Play Protect's
unknown-APK scan, keyed on the app being absent from Google's corpus, not on how
it is signed.

## 1.2.8-r8

## 1.2.9 - Gemini AI features (version unchanged: 1.2.9 / versionCode 13)

Added, all optional and all inert until the user enters their own key:

- `data/GeminiStore.kt` - AI preferences in their own DataStore, so a settings
  reset cannot destroy an API key. The key itself lives in `SecretStore` under the
  new `GEMINI_KEY` alias, sealed with the existing hardware-backed AES-GCM key.
- `ai/GeminiHttp.kt` - HTTPS to Google dialled through the tunnel's own local
  SOCKS5 proxy, destination sent as `ATYP=0x03` (resolved at the exit), TLS
  terminated on-device with hostname verification enforced, chunked transfer
  decoding done on bytes rather than on text so a multi-byte Persian answer cannot
  be corrupted at a chunk boundary. No new dependency: `org.json` is the platform's.
- `ai/GeminiClient.kt` - model discovery (`GET /v1beta/models`, paginated and
  bounded) and `generateContent`, with every failure translated into one of six
  user-facing error kinds.
- `ai/AiGate.kt` - the availability rule: the AI needs a key, a connected tunnel
  and the chained `Aether -> Psiphon` mode, because the app excludes its own
  package from the VPN and Google's AI endpoints refuse WARP exit addresses.
- `ai/AiRedaction.kt` - the log digest that leaves the device: credentials removed,
  the user's own Gemini key removed, public IPv4 masked to /16, private and
  loopback addresses kept, hard character cap.
- `ai/AiPatch.kt` - the ALLOW-LIST that bounds what a model may change: 29 tuning
  keys, each value validated and snapped to the presets the UI itself offers. The
  network backend, upstream proxy, routing lists, manual endpoint, proxy/split/
  blocked-app policy, LAN sharing and every Zero Trust field are deliberately
  absent, with the reason recorded per item in the file.
- `ai/AiTopic.kt` - ~50 settings, each with a factual English description written
  from the engine's real behaviour, so an explanation is grounded rather than
  guessed. `ai/AiPrompts.kt` - the three system instructions and a tolerant parser
  for the JSON contract. `ai/AiSession.kt` - process-lifetime state (settings,
  models, conversation, advice), a bounded explanation cache, debounced key writes,
  bounded chat history and a 90 s floor between automatic analyses.
- `ui/ai/` - the AI icon and explanation sheet, the Gemini-style chat screen
  (asymmetric turns, light Markdown, typing indicator, `imePadding` composer), and
  the AI settings and advisor pages, all built from the existing settings row
  primitives.

Changed:

- `ui/settings/SettingsUi.kt` - every row primitive, `GroupCaption` and
  `SettingsBlock` gained an optional `aiTopic`. The icon is defined once, in
  `BaseRow`, so it cannot drift between row types; with the hints switched off it
  renders nothing and the row metrics are identical to 1.2.8's.
- `ui/settings/SettingsScreen.kt` - three new routes (`AI`, `AI_CHAT`,
  `AI_ADVISOR`), one `LocalAiHost` provider for the whole settings area, and a
  topic on every option in every page. `AI_CHAT` is a navigation ROOT, like
  `QUICK`, so backing out of the chat leaves settings.
- `ui/HomeScreen.kt` - an assistant group at the top of the menu and an AI button
  in the top-end corner, both OUTSIDE the `FitToHeight` subtree so the connect
  button is not scaled down on small phones.
- `MainActivity.kt` - the on-connect analysis runs at the end of the "connected"
  phase, after the self-test has written its results, so the model reads the whole
  connect rather than its first two seconds. `AetherApp.kt` attaches the store.
- `res/values/strings.xml` + `res/values-fa/strings.xml` - 84 new strings each;
  both files verified to hold identical key sets and identical format arguments.
- `README.md`, `README.fa.md`, `native/aether/README.md`,
  `native/aether/README.fa.md` and `.github/release-notes.md` document the feature
  in English and Persian. The 1.2.8 sections were removed from the 1.2.9 release
  notes as requested.

Deliberately NOT changed: `versionName` stays 1.2.9, `versionCode` stays 13,
`PATCHLEVEL` stays 1.2.9 (CI asserts it against the stamp inside the shipped
`libaether.so`, and no native source was touched), and the engine is untouched.

## 1.2.9

### Home screen shortcut
- `ui/HomeScreen.kt`: the top-end tune icon opens the new `SettingsRoute.QUICK`
  instead of `SettingsRoute.HOME`. The drawer's Settings row is unchanged.
- `ui/settings/SettingsScreen.kt`: `SettingsRoute.QUICK` added; it renders the
  same `SettingsHomePage` with `quick = true`, which emits the Tunnel group and
  the reset action only. `SettingsHost` treats QUICK as a stack ROOT, so back
  leaves settings instead of descending into the full tree.
- `res/values*/strings.xml`: `quick_settings_title` / `quick_settings_subtitle`
  in English and Persian.

### Engine core 1.8.0 -> 1.9.0 (rebased by hand)
- Taken from upstream verbatim: `cli.rs`, `masque.rs`, `masque_h2.rs`, the core's
  own docs. New: capsule batching, a dedicated H2 send task, 64 KB DATA frames,
  tier-based H2 flow-control windows.
- Hand-merged, app patches preserved: `lib.rs` (warp-in-warp manual hops,
  `masque_tunnel_mtu()`/`H2_TUNNEL_MTU`, `select_wg_peers(.., avoid)`,
  `select_scan_mode_str(tip)`, upstream's 23 new unit tests) and `sysprofile.rs`
  (upstream's `buffer_override()` + H2 windows adopted; the r4/r6 buffer figures
  kept).
- Left at the app's version on purpose: `netstack.rs`, `wireguard.rs`,
  `wg_prober.rs`, `prober.rs`, `quic.rs`, `upstream.rs` (upstream 1.9.0 does not
  change them beyond what the app patches already rewrote), and the smoltcp
  0.12 + `socket-tcp-cubic` pin in `Cargo.toml`.
- `CORE_VERSION` -> 1.9.0, crate version -> 1.9.0, `rust-version` -> 1.91 (CI
  installs the latest stable, so no toolchain change is needed).
- `.upstream-baseline/` now caches pristine 1.9.0 copies of all ten patched
  files, so the next automatic upgrade has a real merge base for every one.

### Identity
- versionName 1.2.9, versionCode 13, `PATCHLEVEL` 1.2.9.

**Root cause of the upload stall: nothing on the app->network path was bounded in
time, and the queue that held the upload was never measured.**

The first r7 field log settles it. During an upload the uplink sustained
107 KB/s, the latency probe measured a 7173 ms round trip against a 134 ms
session floor - 7 seconds of pure queue - and at the same moment the uplink
writer had waited 0 times, the device queue peaked at 2 of 64 packets, and
`backpressure` and session `tail-drops` were both 0. So the queue was in none of
the places r5, r6 and r7 clamped.

It was in the three that are bounded only by a byte count - the shared `data_in`
channel (1024 messages x up to 16 KB), `TcpState::pending` (256 KB) and the
`Backlog` (512 KB) - plus the loopback legs of `PsiphonSocksFront`, which Android
autotunes into the megabytes. A byte count is a latency bound only if you know
the rate, and nothing here knew the rate: at 107 KB/s those queues add up to
about **eight seconds**, in front of the ONE Psiphon SSH connection that carries
the whole device.

Then the flow went 9.4 s with nothing acknowledged, recovered 2.6 s inside r7's
12 s reset deadline, and the session stayed connected and useless - uplink 6-18
KB/s for three minutes while the badge read a healthy 140-260 ms - until the user
reconnected by hand. Full analysis and log evidence in
`docs/LIVE_STREAM_STALL_1.2.8-r8.md`.

### Fixed - per-flow uplink admission, measured in time

- Every flow carries a `FlowCredit`. The app may only have
  `handed-to-the-stack - acknowledged-by-the-peer` bytes in the pipe, capped at
  that flow's **own measured drain rate x 500 ms**, clamped to
  `[48 KB, tcp_tx_buf()]`. Past it the SOCKS5 reader stops reading, so
  backpressure crosses the loopback leg, reaches hev-socks5-tunnel and the TUN,
  and ends up in the congestion window of the app doing the upload.
- The rate is derived from r7's acknowledged-bytes signal, the only rate here a
  dead path cannot fake. The floor is above this path's bandwidth-delay product
  so admission can never be the throughput limit, and the ceiling is
  `tcp_tx_buf()` so r8 can only ever remove queue that r6 already permitted.
- Measured effect on the reported session: ~53 KB outstanding instead of
  ~900 KB, i.e. ~0.5 s of standing queue instead of ~8 s.

### Fixed - the loopback legs were the next hidden queue

- `PsiphonSocksFront` pins `SO_SNDBUF`/`SO_RCVBUF` to 64 KB per direction on both
  loopback legs. Without it the queue r8 squeezes out of the netstack simply
  reappears in Android's loopback autotuning, which is the same mistake r6 found
  on the WireGuard socket.

### Fixed - a stall that self-recovers is no longer treated as a recovery

- `TCP_DRAIN_STALL_TIMEOUT` 12 s -> 8 s. The observed stall was 9.4 s, so it now
  ends in a reset, and in chained mode a reset costs one automatic Psiphon redial
  instead of a manual disconnect and reconnect.
- New `STALL_FLAP_LIMIT`: two stalls of >=3 s inside 60 s reset the flow even when
  each one recovered on its own.

### Fixed - the telemetry reported samples where it needed maxima

- `[netstack]` gains `uplink peaks: outstanding N B, pending N B, socket N B,
  budget N B`. `outstanding` and `pending` were never reported before, all three
  are window high-water marks rather than end-of-window samples, and `budget`
  states the rule admission is enforcing. `socket send-queue 3792 bytes` logged
  in the same second as a 4803 ms probe is exactly the reporting gap this closes.

Version stays 1.2.8 / versionCode 12. Patch level only.

## 1.2.8-r7

**Root cause of the live-stream / dubbing stall, after six rounds of misses.**

The netstack measured flow liveness on the wrong event. `TcpState::last_progress`
was refreshed whenever a socket *accepted* bytes into its send buffer, which only
proves the socket agreed to remember them, not that the peer received anything.
A flow whose peer had gone silent therefore looked healthy until its 128 KB send
buffer was 100% full, and `reap_wedged` only ever considered flows in
`backlog.blocked`, which such a flow never enters. The r6 field log shows the
worst flow at exactly 131072 bytes queued emitting one packet in fifteen seconds
with `backlog 0`, `backpressure 0`, `tail-drops 0` and no warning at all. The
30 s wedge clock only started when the buffer saturated; it would have fired 14 s
after the session ended.

In a chained `Aether -> Psiphon` session Psiphon multiplexes the whole device
over one SSH connection, so that single dead flow is every app on the phone:
download collapses while upload trickles on keepalives, which is the reported
symptom exactly.

- Liveness is now derived from **acknowledged bytes**
  (`accepted_total - send_queue()`), the only signal here that requires the far
  end to have actually received data. A naive "did the send queue shrink" check
  was rejected: a saturated upload pins its queue at the buffer limit and would
  have been reset while working perfectly.
- Non-draining flows are reported at 3 s and reset at 12 s, sized against the
  session's measured 142-238 ms RTT.
- `reap_wedged` now scans all flows, before the `backlog.blocked` early return
  that made this case unreachable.
- New `[netstack]` telemetry: `unacked-for <dur> (worst flow), N flow(s) not
  draining` - distinguishes a deep queue that is moving from one that is dead.
- Latency badge: r6 fixed the post-re-warm sample and left the steady-state one
  unfiltered and unlogged, which is why the log only ever held 142-238 ms while
  the screenshot showed 6442 ms. It now keeps a session floor, re-checks an
  outlier once and reports the lower sample, and logs every reading.
- r6's uplink `SO_SNDBUF` clamp is confirmed correct and live (`writer waited 0`
  with the uplink peaking at 146 KB/s); it was simply not this bug.

Version stays 1.2.8 / versionCode 12. Patch level only.

# Changelog

## 1.2.8-r6 - the uplink queue was in the kernel

Version unchanged (**1.2.8**), patch level `1.2.8-r6`. Full analysis with log
evidence in `docs/LIVE_STREAM_STALL_1.2.8-r6.md`.

The r5 provenance work paid off: the field log is stamped `1.2.8-r5`, so for the
first time every previous conclusion could be tested. They all came back negative
- `backlog 0`, `tail-drops 0`, no starvation, no watchdog rotation, no teardown -
on a session where a fresh dial cost 5694 ms and the badge read 3092 ms.

### Fixed - ROOT CAUSE: a 7 MB kernel send buffer disabled every throttle above it

- The 1.2.8 media-stall fix set `SO_RCVBUF` **and** `SO_SNDBUF` from one figure, so
  every WireGuard socket got a 7 MB send buffer (two of them in series in gool
  mode). `send()` on a datagram socket is only backpressure when that buffer is
  full, and 7 MB never is - so the writer never waited, the mpsc never filled,
  `StackDevice.tx` never reached its gate, `transmit()` never refused, and CUBIC
  was still being shown an infinite, lossless link. `backpressure 0` on all 96
  telemetry lines of the r5 log was the receipt, not a clean bill of health.
- `sysprofile` splits the figure: receive stays generous (that half was right and
  it prevents real loss), send becomes a latency budget - 128 KB on a high tier,
  ~250 ms of a mobile uplink, down from 7 MB. A kernel that refuses to shrink it
  now logs a WARN instead of silently restoring the bug.
- The WireGuard writer uses `try_send` + `writable()`, so a full uplink is a timed,
  counted wait that propagates all the way to the congestion window.
- Netstack TCP send buffer 512 KB -> 128 KB. In chained mode the whole device rides
  ONE flow (`tcp flows=1`), and 512 KB in front of it is ~10 s of a live dubbing
  uplink.

### Fixed - the telemetry was pointed at the wrong side of a syscall

- New `[uplink <peer>]` line per tunnel per 15 s: packets, KB/s, the granted
  `SO_SNDBUF`, and how often and how long the writer had to wait. This is the
  measurement that was missing in r2, r3, r4 and r5.
- `[netstack]` gains `socket send-queue N bytes (worst flow M)` - what smoltcp has
  accepted but not yet put on the wire. `backlog` (always 0) is what has not been
  handed to a socket. Together they separate "nothing to send" from "cannot send".

### Fixed - the latency badge reported its own dial as the ping

- The first round trip on a just-re-warmed session rides a connection whose
  handshake was itself queued. The r5 log: `setup 5694 ms, round trip 3092 ms`,
  then 35 s later `setup 327 ms, round trip 191 ms` on the same path. r6 takes a
  confirming sample and reports the lower of the two, logging both - the gap
  between them is the uplink queue depth.

### Fixed - the same CI time bomb r5 found, in three more files

- `sync-core.sh` discovers app patches from `AETHER-APP-PATCH` markers and deletes
  everything else on a core upgrade. `upstream.rs`, `quic.rs` and `wireguard.rs`
  carried no markers and now hold the r6 fix, so the first upstream release would
  have removed it with a green build. All three are now wrapped.

## 1.2.8-r3 - live-stream stall, root-caused

Version unchanged (**1.2.8**). Full analysis with log evidence in
`docs/LIVE_STREAM_STALL_1.2.8-r3.md`; it also documents why the r2 diagnosis was
wrong.

### Fixed - the app was tearing down its own working session

- Watchdog and latency probes dialled anycast resolvers on **TCP/53**, a port a
  large share of Psiphon exits refuse. Every check therefore failed on a healthy
  tunnel. Probes now do a real TLS handshake on **443**.
- A failed probe could rebuild the whole session on its own. It now also requires
  the tunnel core's byte counters to be flat: a path moving traffic is not wedged.
  The r2 log tore down a session that had moved 703 KB.
- The pipeline probe raced `PsiphonHealth`'s deliberate exit rotation and killed
  the session 3.9 s into the repair. It now stands down while a rotation settles.
- Probe destinations are registered as self-probes and can no longer count as
  evidence that an exit filters, so the app cannot trigger its own rotations
  (which drop udpgw and kill every UDP/QUIC flow on the device).
- The latency badge tries three endpoints before reporting an error.

### Fixed - the engine serialised uploads against downloads, per packet

- The WireGuard send task took the boringtun session lock once per packet, against
  the socket reader doing the same. Harmless on a download, fatal on a symmetric
  live stream: every packet cost a scheduler round trip. Bursts are now
  encapsulated under one acquisition (`MAX_ENCAP_BATCH = 64`), with no added
  latency.
- The one-shot `obf_sent` mutex is no longer taken for every outbound datagram.

### Fixed - netstack starvation telemetry cried wolf

- The `select!` arm receiving `data_in_rx` did not count as app->network progress,
  and the report triggered on a single inbound packet. r2 therefore reported
  32-second upload stalls on idle tunnels. The signal now requires a genuinely
  busy download direction and a non-empty backlog.

## 1.2.8-r2 - live two-way streams no longer starve the tunnel

Fix pass inside 1.2.8; the version stays **1.2.8**. Full analysis:
`docs/LIVE_STREAM_STALL_1.2.8-r2.md`.

- **netstack: the app->network path was starved by any sustained download.** The
  run loop's `select!` was `biased` with inbound first, so while packets kept
  arriving the upload arm and the open-a-flow arm were never polled at all. One
  18-minute field log: 115 MB received against 6 MB sent, with a symmetric live
  dubbing session running the whole time. Every queue now gets its own per-pass
  budget and the visit order alternates; `select!` is only reached when all of
  them are empty.
- **netstack: starvation is now reported** in one log line instead of having to
  be inferred from a byte ratio.
- **Psiphon front: one udpgw port forward, not two.** A Psiphon tunnel holds only
  the newest one, so the DNS and bulk lanes added in 1.2.7-r3 evicted each other
  219 times in 18 minutes (218 of 218 consecutive dials alternated lane). DNS is
  prioritised inside the single stream instead. udpgw dials are rate-limited with
  exponential backoff and counted.
- **Bufferbloat: the packet handoff queues were application-sized.** 1024 packets
  per direction plus 2048 retained is several seconds of standing local queue on
  a mobile uplink. Capped at 256; throughput is set by smoltcp's socket buffers,
  so nothing is lost but the delay.

## 1.2.8

### Fixed - the mid-video stall, at the root (`docs/MEDIA_STALL_1.2.8.md`)

Reported symptom: `Aether -> Psiphon` connects and browses fine for about a
minute; a YouTube video then pins the ping at ~2000 ms and the session stops
carrying anything at all - not the video, not any other site - while the app,
both stages and every local port stay up. Only a manual disconnect/reconnect
recovered it. The same freeze occurred on plain `Aether`, which is what located
the fault: everything the two modes share is the engine's data plane.

Five defects, all on that shared path, all now fixed.

- **An expired WireGuard session was ignored instead of being replaced.**
  `Tunn::update_timers` reports "this session is finished" exactly once, and the
  timer task pattern-matched only the `WriteToNetwork` arm - the report went in
  the bin. From that moment `encapsulate` refused every packet (a `trace!` line
  each), so the tunnel object, the netstack and the SOCKS5 listener were all
  alive and healthy around a crypto session that was dead. That is the freeze,
  and it was permanent by construction. The engine now carries the peer's keys
  with the session and **re-handshakes in place on the same socket** - the
  netstack, the listener and Psiphon's upstream never even notice. Both the send
  path (a run of refused packets) and the health task (a quiet data plane) can
  ask for it, rate-limited to one attempt every 3 s.
- **One stalled connection froze every other connection.** The netstack's run
  loop kept a single global deferred queue and gated its only app-data intake on
  it - `data_in_rx.recv(), if deferred.is_empty()`. A flow whose peer stopped
  reading therefore blocked the writes of every other flow, DNS included. The
  backlog is now ordered **per flow**: a stuck flow queues behind itself and
  nobody else waits. A flow that accepts nothing for 30 s is reset, and TCP
  keep-alive plus a dead-peer timeout are set on every socket so a black-holed
  connection can no longer live for the length of the session.
- **The tunnel's UDP sockets never got the buffers the app sized for them.**
  `sysprofile` has been computing them (and logging "udp socket buffer=7168KB")
  since 1.2.5, but only the QUIC/MASQUE path applied it: the entire WireGuard
  and gool data plane ran on the OS default. That is a few milliseconds of a 4K
  stream, so the first time the reader was late the kernel began discarding
  datagrams - including the handshake replies the session needed to survive.
- **The socket readers could park indefinitely.** Both the WireGuard reader and
  the gool relay handed packets on with an unbounded `send().await`, so a busy
  netstack made the *only* reader of the socket go deaf, which caused the loss
  in the point above. Handoff is now bounded at 20 ms: late is fine, deaf is not.
- **A congested burst was shredded, not held.** `flush_tx` dropped every
  remaining packet the moment the writer's channel was full. It now holds the
  burst and retries 2 ms later, tail-dropping only past a real queue limit -
  which is where the 2000 ms ping came from in the first place.
- **The watchdog could not see any of this.** Its probe was a bare SOCKS5
  `connect()`, and a connect is answered by the accept path, not the data path:
  a wedged tunnel passed every check forever, which is why nothing ever healed
  by itself. The probe now completes a real **DNS round trip** and requires a
  valid answer. A chained session, which previously only checked "is the Psiphon
  library still running?", now gets the same end-to-end probe aimed at the
  pipeline's own listener every 15 s and rebuilds itself after two dead
  readings.
- **A congestion spike no longer counts as a dead peer.** The data-plane stale
  timeout went from 10 s (shorter than a bad mobile path stalls on its own, so a
  video's first spike tore down a perfectly good gool session, both hops, and
  charged the user a rescan) to 20 s, with two confirmations, and an in-place
  re-handshake attempted at half that.

### Changed
- Version 1.2.8, versionCode 12.
- Watchdog cadence 30 s -> 15 s, failure threshold 3 cycles -> 2: the probe now
  proves something, so it can act sooner. Probe timeout 8 s -> 5 s.


## 1.2.7

### Fixed (app-connectivity pass, version unchanged - `1.2.7-r3` in code comments)
See `docs/PSIPHON_APP_CONNECTIVITY.md` for the full analysis.
- **AI apps, CapCut and the browsers work in `Aether -> Psiphon` again.** Three
  black holes were reported as "you have no internet" by every app that will not
  fall back from HTTP/3, and the reporter's own control experiment named them: the
  same session, USB-tethered to a laptop, opens Gemini instantly - because a
  tether path is TCP-only and IPv4-only.
- **UDP/443 is carried again** (`CARRY_QUIC`). r2 dropped it silently, and SOCKS5
  `UDP ASSOCIATE` cannot return an ICMP port-unreachable, so a dropped datagram is
  indistinguishable from a dead link. r2's two real mechanisms are fixed at source
  instead: **DNS now has its own udpgw stream** (a priority queue could not help,
  because the blocking happens in the kernel send buffer below it), and the bulk
  lane **drops rather than buffers**, which is exactly the loss signal QUIC's
  congestion control needs. A self-expiring breaker still suppresses UDP/443 for
  60 s under a genuine storm.
- **The device is no longer told it has IPv6 by an IPv4-only exit.** A chained
  session's TUN carries no IPv6 *address* while still routing `::/0`, so Android's
  own resolver filters AAAA per-network and every app settles on IPv4 - and the
  front answers AAAA with an empty NOERROR once the exit has proven it cannot dial
  IPv6. That proof is now obtained up front, once, instead of being paid for by the
  first two app flows of every session (65 refused IPv6 flows in one two-minute log).
- **The sanctions page is gone.** `::/0` is routed unconditionally in a chained
  session: an uncaptured IPv6 flow left with the phone's real address, so the site
  answered with a country block while the app still said Connected.
- **"Only Telegram and Instagram open" was name resolution.** The udpgw dial ran on
  the datagram pump thread, holding a lock every association needed - the pump is
  the only reader of the forwarder's UDP socket, so UDP stopped for the whole
  device, DNS included, for the length of the dial. Dialling moved to its own pool,
  both lanes are pre-warmed before the TUN comes up, and the DNS-over-HTTPS
  fallback now pools and reuses connections instead of paying a port forward plus a
  TLS handshake per name.
- **A SOCKS handshake can no longer hang forever.** `openPsiphonStream` read it with
  no timeout at all, so a listener that accepted and never answered parked the
  caller for the life of the session.
- **A refused TCP/853 is named in the log.** That is where Android's Private DNS
  goes; in strict mode there is no fallback, so the device resolves nothing while
  the tunnel is healthy. Not counted as censorship.
- `hev.yaml`: `udp-read-write-timeout` 60000 -> 120000, because a live QUIC
  connection legitimately idles now.

### Fixed (stability pass, version unchanged)
- **The app no longer disconnects itself while healing a bad exit server.**
  `PsiphonHealth`'s last-resort rotation calls `restartPsiphon()`, which stops the
  Psiphon library in place; that fires `onExiting`, which cleared
  `PsiphonTransport.connected`; and the chained supervisor in `AetherVpnService`
  polls `transport.isAlive()` once a second and tore the whole session down the
  moment it read false. The log caught it exactly: `Psiphon: Exiting: {}` at
  08:06:38.535, `hev-socks5-tunnel stop requested` 0.74 s later. `isAlive()` now
  masks a bounded rotation window (`ROTATION_GRACE_MS`, self-expiring), and the
  supervisor additionally requires `TRANSPORT_DEAD_CONFIRMATIONS` consecutive dead
  reads before rebuilding a session.
- **Rotations actually change server now.** The old rotation dropped a working
  tunnel and rebuilt the identical one: psiphon-tunnel-core ranks the last
  connected server first via dial-parameter replay (`isReplay: true`) and server
  affinity (`moved-to-front`), and from the library's point of view a server that
  refuses port forwards is excellent - the tunnel established and bytes moved.
  Log: `rotating off server OsBTQokd` at 08:03:43.044, `ConnectedServer ... OsBTQokd`
  at 08:03:45.759. Convicted servers now go on a session blacklist together with
  their exit region, and a `RESTART` rotation rebuilds the config with
  `DisableReplay`, `EstablishTunnelServerAffinityGracePeriodMilliseconds: 0` and -
  when the exit is Automatic - a round-robin steer to a different *reachable*
  egress region, which is the only hard server filter the library exposes. A
  hand-picked country is never silently changed.
- **Landing back on a blacklisted server is detected instead of ignored.** The old
  code keyed its reset on the active server *changing*, so re-connecting to the
  same id did nothing and it had to re-earn six refusals. Every `ActiveTunnel`
  notice is now a verification point: a known-bad server is re-rotated at once.
- **The rotation budget is no longer burned in three seconds.** Refusals draining
  out of an abandoned tunnel were counted against its replacement - hence
  "refused 6 different destinations" five times inside 300 ms in the log. A
  `SETTLE_MS` grace period per tunnel attributes them correctly, counters for a
  server that is not the active one are dropped, and a server that stays clean for
  three minutes refunds the budget so a long session is not left defenceless.
- **Ping spikes, drop/reconnect churn and low throughput on a filtering server**
  are the same root cause: every rotation dropped the udpgw session and every
  in-flight flow, and the rotations were looping. With the loop closed, UDP (DNS +
  QUIC) survives a rotation and `PsiphonSocksFront.retarget()` follows Psiphon to a
  new local port without ever closing the listener tun2socks is connected to.
- **Persian is right-to-left even when the phone is in English.** `attachBaseContext`
  produced a correct `Configuration`, which is why the text was Persian, but Compose
  lays out from `AndroidComposeView.onRtlPropertiesChanged` - the direction the VIEW
  tree resolved - and the decor view resolves that against `Locale.getDefault()`,
  which the framework rewrites from the activity's own locale list after
  `attachBaseContext` has run. On a Persian phone it wrote back the value we wanted
  and hid the bug; on an English phone it wrote `en` over our `fa` and the whole UI
  laid out LTR. `AetherTheme` now pins `LocalLayoutDirection` from the stored
  language choice (`LanguagePrefs.isRtl`), and `LanguagePrefs.applyLayoutDirection`
  pins the window so dialogs, popups and selection handles agree. Covers dialogs,
  bottom sheets and dropdowns, which read the same composition local.
- `LanguagePrefs.findActivity()` walks out of any `ContextWrapper`, so the language
  switch's `recreate()` can no longer no-op on a wrapped `LocalContext`.

### Added
- **Vazirmatn is bundled** (`res/font/vazirmatn_bold.ttf`) and applied across all
  fifteen Material type roles whenever the app renders Persian, so every screen,
  row, button, dialog and sheet uses it instead of falling through to whatever
  Arabic-script fallback the device happens to ship. Technical readouts (timers,
  rates, IP:port, the diagnostics log) stay monospaced so their columns keep
  aligning. Only the Bold face ships, so every weight is mapped to it explicitly
  to avoid synthetic emboldening; see `ui/theme/Type.kt` to add a real weight ramp.
- **A settings screen** replacing the single collapsible "Advanced settings" card:
  a category list, controls grouped into cards, bottom-sheet pickers with the
  current option ticked, a large collapsing title per page, and the current value
  of each category shown on its row. Reachable from the home screen's top-right
  icon or the side menu. New package `ui/settings/` (`SettingsUi.kt` design
  system, `SettingsScreen.kt` host and pages).
- **In-app language selection** (`data/LanguagePrefs.kt`): Follow the phone /
  English / Persian, applied in `attachBaseContext` so the first frame is already
  correct, and wired into the Activity, the Application, the VPN service, the
  Quick Settings tile and the home-screen widget. Persian sets the layout
  direction to RTL; technical fields stay LTR. On API 33+ the choice is mirrored
  into `LocaleManager.applicationLocales`.
- **`fastEndpointOnly` ("Only reuse a fast endpoint")**, on by default, exposed
  under Transport & anti-DPI. Sends `AETHER_QUICK_RECONNECT_MAX_RTT_MS` /
  `AETHER_QUICK_RECONNECT_MAX_HANDSHAKE_MS`, with a tighter budget for the chained
  hop (`ConnectionProfile.chainedStage`).
- **Automatic Psiphon exit-server rotation** (`transport/PsiphonHealth.kt`) when a
  server refuses many distinct destinations in a sliding window, or its
  `port forward failures` counter climbs past a threshold: `reconnectPsiphon()`,
  escalating to `restartPsiphon()`, rate-limited and capped per session.
  `PsiphonSocksFront.onServerRotated()` drops the udpgw session and clears the
  refusal latch so the new server gets a fresh chance at real UDP.
- A confirmation dialog before "Reset all settings to defaults".

### Changed
- **The colour scheme is pinned.** `AetherTheme` no longer calls
  `dynamicDarkColorScheme()`, which repainted every themed surface from the
  wallpaper on Android 12+ while the connection card pinned brand colours -- so
  half the app was on brand and half was not, differently on every device. One
  hand-built eight-step navy ramp (`ui/theme/Color.kt`) now backs every surface
  role, including Material's `surfaceContainer*` tints.
- **UI performance.** `ConnectionProfile` is `@Immutable` (it was inferred
  unstable because of its `List<String>` members, so no composable taking a
  profile could ever be skipped); settings pages are lazy lists of independently
  lazy sections; the navigation drawer is a menu instead of a host for four live
  panels; the home screen is disposed while settings is open; and profile writes
  are debounced (300ms) with an `onStop` flush instead of one DataStore commit per
  keystroke.
- `ProfileCodec` now carries the in-tunnel DNS servers, both routing-rule lists,
  domain sniffing and its window, the upstream proxy, identity replacement and the
  Zero Trust enrolment fields, so those settings reach the engine at launch. The
  two Zero Trust secrets are deliberately still absent from the Intent payload:
  `AetherVpnService.hydrateSecrets()` reads them from the hardware-backed
  `SecretStore` instead.
- Engine (`native/aether/aether/src/lib.rs`, inside `AETHER-APP-PATCH` markers): a
  cached quick-reconnect endpoint is reused only when it is still fast, not merely
  alive. A field log shows one accepted at `rtt 472ms` while 100-143ms edges had
  just been measured, which roughly halves throughput -- twice over in a chained
  session. Stripping the markers reproduces the pristine upstream file byte for
  byte, and `lib.rs` is registered in `PATCHED_FILES`.
- The backend and exit-country help text is localised; it was hard-coded English.

### Fixed
- **`Aether -> Psiphon` connected and opened nothing.** hev-socks5-tunnel carries
  all UDP (and therefore all device DNS) over SOCKS5 `UDP ASSOCIATE`, and
  psiphon-tunnel-core's local SOCKS proxy is CONNECT-only: `socks5ReadCommand:
  SOCKS message field command was 0x03, not 0x01`, 636 times in one 50-second
  session, 267 bytes transferred, no page loaded. New `PsiphonSocksFront` binds
  `127.0.0.1:1825` (the port tun2socks talks to) and chains onto Psiphon at
  `127.0.0.1:1827`, carrying real UDP over Psiphon's remote udpgw
  (`127.0.0.1:7300`, one shared stream multiplexed by connection id, exactly as
  badvpn's `SocksUdpGwClient` does) so DNS *and* QUIC work. If a server refuses
  the udpgw port forward, port-53 datagrams fall back to DNS-over-TCP through the
  same tunnel and non-DNS UDP is dropped, so name resolution never depends on the
  intercept being available.
- **The VPN interface terminated the Psiphon tunnel seconds after connecting.**
  `setVpnMode(false)` made PsiphonTunnel's network monitor read our own TUN as a
  network change (`NetworkMonitor: set current active network VPN with DNS` ->
  `terminated tunnel` -> `tunnel failed`) and adopt the TUN's advertised resolvers
  as its own, which pointed back through the tunnel it was establishing. VPN mode
  is now declared; PsiphonTunnel 2.x runs no tun2socks or routing of its own, so
  the flag only affects network monitoring and DNS selection.
- Both chained transports now report their FRONT's port from `start()`, so
  tun2socks can never again be handed a listener that refuses `UDP ASSOCIATE`.

### Added
- **A fifth self-test step: Device DNS (SOCKS5 UDP).** The four existing checks
  stayed green through the entire broken Psiphon session, because the DNS check
  resolves via a `CONNECT` carrying a hostname (resolved remotely on the TCP
  path) while the device uses `UDP ASSOCIATE`. The new check speaks the same
  protocol hev does against the same port and **gates the Connected state**.
- Engine core upgraded to **1.8.0** (world-reachable listener warning,
  non-blocking HTTP head read, upstream-proxy SOCKS5 auth hardening, and a fix
  for the tokio "JoinHandle polled after completion" panic in Gool teardown).
  `cli.rs` / `config.rs` are byte-identical to 1.7.0, so there are no new engine
  flags to surface. Both manual-range patches were rebased onto the new sources
  and, apart from the `AETHER-APP-PATCH` blocks, the vendored tree is
  byte-identical to upstream. Baseline floor raised to 1.8.0 and a pristine
  1.8.0 merge base cached in `native/aether/.upstream-baseline/`.
- **An animated ping-strength meter** on the latency slide, which otherwise left
  most of its width empty: a travelling waveform of rounded bars whose height,
  brightness and colour follow the last probe (mint / amber / rose) with a
  quality word beside it. The travel speed is fixed on purpose - deriving it from
  the latency changed the animation spec on every probe, which restarts an
  infinite transition and made the wave jump once every four seconds.
- The latency slide keeps the **last good reading**. `PingMonitor` publishes
  `ms = -1` while a probe is in flight, so the value used to blink to an ellipsis
  and the meter collapse for a moment every four seconds.
- Strings for the meter in English and Persian (`meta_ping_strength`,
  `ping_quality_*`).

### Removed
- **Single-hop `Psiphon` and `Tor` backends.** Neither could get its own first
  hop past the networks this app targets, so both sat on "Connecting" and timed
  out while the chained modes connected in seconds. Saved profiles are MIGRATED,
  not reset: `PSIPHON` -> `AETHER_PSIPHON`. The Tor names resolve to `AETHER`,
  because this release removes the Tor mode entirely - see the next bullet.
- **The Tor backend, entirely.** Both the protocol and the chained
  `Aether -> Tor` mode are gone from Advanced -> Network backend, and so is
  everything behind them: `transport/TorTransport.kt`,
  `transport/TorSocksFront.kt`, `scripts/build-tor.sh`, the packaged `libtor.so`
  executable, `TunnelConfig.TOR_SOCKS_PORT` / `TOR_DNS_PORT` (1822 / 1823), the
  `ExternalKind.TOR` and `TransportBackend.AETHER_TOR` enum values, the
  release-CI "Build Tor native core" step and the Tor entries in the
  third-party notices. Tor carries TCP streams only, so every datagram the
  device sent had to be answered out of Tor's own `DNSPort` and everything else
  dropped (no QUIC, and DNS depending on an adapter faking a protocol Tor does
  not speak), and `Aether -> Tor` paid a full Tor bootstrap *through* the Aether
  handshake, which was slow enough to read as a hang. Psiphon delivers the same
  thing - a foreign exit behind Aether's obfuscated first hop - carries real UDP
  and comes up in seconds, so it is the one external transport that ships.
  Backends are now **Aether** and **Aether -> Psiphon (chained)**.
- Saved profiles: a stored `TOR` / `AETHER_TOR` resolves to plain `AETHER`, NOT
  to `AETHER_PSIPHON`. Migrating a profile that asked for a Tor exit onto a
  different provider's network would swap one trust model for another without
  telling the user. `PSIPHON` still migrates to `AETHER_PSIPHON`.
- The two Tor Kotlin files are listed in `.github/removed-sources.txt`, so a
  release built on top of an older checkout purges them instead of failing to
  compile against symbols that no longer exist.

### Changed (UI pass 2, same version)
- **The home screen no longer scrolls: it fits, by construction.** The column was
  a `verticalScroll`, so on a shorter screen - or with a larger system font, or in
  Persian where several strings wrap - the end of the connection card sat below the
  fold and part of it stayed under the navigation bar. Hand-tuned `Spacer` heights
  only moved that problem to the next screen size. New
  `ui/components/FitToHeight.kt` measures the content against an unbounded height,
  and when it is taller than the viewport it overrides `LocalDensity` for the whole
  subtree by one measured factor - type, paddings, icons, radii and stroke widths
  together - until it fits. The search starts at `1f`, only ever shrinks (so it is
  monotone and cannot oscillate), converges in one or two passes inside the same
  frame, bottoms out at `0.55f`, and re-runs when the viewport or the system font
  scale changes. Density is overridden rather than `graphicsLayer { scaleX = ... }`
  on purpose: this is a real layout at a real density, so text is rasterised at the
  size it ends up being and the scaled UI is exactly as sharp as the unscaled one.
- **The travelling ring around the connect button is gone.** Two light shows on
  one screen competed for the eye, its bloom needed a 220 dp box for a 150 dp
  button (70 dp of padding at the top of the screen, almost exactly the height the
  content block was missing at the bottom), and it cost a second set of additive
  strokes per frame. The card edge keeps the light. Button geometry came down with
  it: box 220 -> 190 dp, disc 150 -> 132 dp.
- **A tick replaces the bolt on the connected button** (`Icons.Rounded.Check`,
  84 dp). A bolt reads as "power", which is what the idle button already says; a
  tick reads as "you are through". The soft additive core behind the glyph stays.
- **The travelling light runs in the PRIMARY colours:** red, green, blue, yellow -
  one per lap, wrapping. The previous six-colour palette (mint, cyan, azure,
  violet, rose, amber) was six tints of the same cool corner of the wheel, three
  of them barely distinguishable at hairline width on a navy card. The two cool
  primaries are luminance-trimmed rather than mathematically pure, because this
  light is drawn additively on `#0A0E1A`: pure `#0000FF` has too little luminance
  to read as light and pure `#00FF00` clips its own bloom to white.
- **The light is drawn at higher fidelity.** The bloom was three strokes over a
  4.4x falloff with a highlight lerped 42% toward white, which at peak amplitude
  stacked into a hard-edged white blob with visible alpha banding - the "pixelated"
  look. It is now five graded strokes over a 3.2x falloff, a smoothstep on the
  amplitude, a peak alpha held below saturation, a 30% highlight, and
  `StrokeJoin.Round` on every stroke (a mitred join spiked visibly on the card's
  26 dp corners). The light is also drawn 1.5 dp wide instead of exactly 1 dp,
  which lands on a device-pixel boundary far more often.
- **The card's vertical rhythm tightened again** to spend less of the fit budget:
  inner padding 18 -> 16/15 dp, section spacing 14 -> 11 dp, status 30 -> 27 sp,
  timer 38 -> 33 sp, ping meter 26 -> 22 dp, IP pill and meta rows one dp tighter.
- **About is reordered: this edition first.** The Android app / GUI author
  (QW-AI-Code) and its feature list open the panel, with the upstream Aether engine
  (Cluvex Studio) credited underneath, and the chained `Aether -> Psiphon`
  transport is listed among what this edition adds.
- **Nothing on the button animates unless it must.** The halo pulse is composed
  only while connected and the sweep only while busy, both read inside draw/layer
  lambdas, so an idle screen holds no frame subscription and a frame costs a redraw
  rather than a recomposition.

### Security (audit, same version)
- **The LAN proxy bridge now fails closed.** `ShareBridge.start`/`startSync` had
  `localOnly = false` as their DEFAULT, so any call that omitted the argument bound
  an unauthenticated SOCKS5 + HTTP proxy on `0.0.0.0:10810/10811` for the whole
  network. Every existing caller passed the flag, so behaviour is unchanged - but
  the default is now `true` (loopback) and the two LAN call sites in `SharePanel`
  ask for exposure explicitly.
- **Backup rules are actually wired up.** `res/xml/backup_rules.xml` existed and
  was referenced by nothing; `android:allowBackup` was the only thing standing
  between the data dir (manual endpoints, diagnostics log, sealed secrets) and a
  cloud backup. The manifest now declares `android:fullBackupContent` and a new
  `res/xml/data_extraction_rules.xml` denies both cloud backup and device-to-device
  transfer for every domain on Android 12+.
- **Dead export surface removed.** `res/xml/file_paths.xml` described a
  FileProvider that went with the in-app updater in 1.2.2. Deleted and registered
  in `.github/removed-sources.txt`.
- **Orphaned Tor build script removed.** `scripts/build-tor.sh` survived the Tor
  removal, cross-compiled a `libtor.so` nothing loads, and was documented as
  already deleted. Deleted for real, registered for purge, and the purge
  allowlist now accepts `scripts/` paths.
- Full findings, including the accepted risks and what was verified clean, in
  `docs/SECURITY_AUDIT_1.2.7.md`.

### Changed
- **Protocol, endpoint and latency each get their own full-width slide** inside
  the connection card, replacing the single three-column strip. Each column was
  a third of a phone screen wide, which is not enough for the values it carried:
  `WIREGUARD` rendered as `WIREGUARD ...` and an endpoint (`ip:port`, 21 chars
  for IPv4, more for IPv6) was almost always `...`. Label left, value right with
  the whole card width, two lines for a long endpoint. Everything stays inside
  the same card and the card keeps its proportions.
- **The layout above the card tightened to pay for that height.** Screen padding
  32 -> 18 dp, the gap under the title 28 -> 10 dp and the gap under the connect
  button 28 -> 6 dp, so the button and the card both move up instead of the block
  sliding off the fold. The card's internal rhythm went 16 -> 14 dp.
- **The travelling border light runs one colour per lap.** It was a fixed
  mint-to-cyan blend; it now holds one colour for a full lap of the perimeter and
  takes the next colour of the palette (mint, cyan, azure, violet, rose, amber)
  on the next lap, wrapping back to the first after the last. Implemented as ONE
  `animateFloat` from 0 to the number of colours, so the lap index is its integer
  part and the wrap has no seam: no crossfade to schedule, no second animation to
  resynchronise.
- **The connect button shows the same light show once connected.** The same
  bands, colours and lap, from the same `rememberGlowCycle` clock, drawn around
  the button's disc by the same helper the card edge uses (new
  `ui/components/GlowCycle.kt`), so the two surfaces cannot drift apart. Nothing
  subscribes to a frame callback while disconnected.
- **One big bolt.** The connected icon went from a 58 dp spark in a 150 dp disc
  to a single 96 dp bolt with a soft additive core behind it.

### Added (earlier in this release)
- Backend selector for Aether, Psiphon, and Tor. Both Tor entries were removed
  later in the same release: what ships is Aether and the chained
  `Aether -> Psiphon` (see Removed above).
- **Chained backends: `Aether -> Psiphon` and `Aether -> Tor`** (the Tor one was
  removed again before release). The Aether engine
  comes up first as a local SOCKS5 proxy, the second stage dials out through it,
  and the TUN is pointed at the second stage. The public exit IP becomes Psiphon's
  or Tor's while the only hop the local network sees is Aether's obfuscated
  transport. Psiphon rides it via `UpstreamProxyUrl`, Tor via `Socks5Proxy` in
  `torrc`. Selectable under Advanced -> Network backend.
- Exit-country selector with automatic mode, now with **flag emoji** next to every
  country name (globe for Automatic) and a much wider list: the practical union of
  Psiphon's egress regions and the countries Tor has reliable exits in.
- Psiphon Tunnel Core 2.0.39 integration and embedded signed server-entry bootstrap list.
- Direct Tor runtime with DNS-aware SOCKS front and optional non-strict ExitNodes preference. (Removed again in this release.)
- Pinned Tor native build script and release-CI build step for arm64-v8a and armeabi-v7a. (Removed again in this release.)

### Fixed
- **Crash on Tor disconnect (`FATAL on thread 'tor-log'`).** The Tor stdout reader
  was an unguarded `forEachLine` on a bare thread. Stopping Tor closes that stream
  mid-`readLine`, and the resulting `IOException("Stream closed")` reached the
  process-wide handler and killed the app during an ordinary teardown. The reader
  is now guarded and carries its own uncaught handler; an expected end-of-stream is
  logged as the normal event it is.
- **Tor never reported Connected.** Tor opens its `SocksPort` ~100 ms after launch,
  long before it has a consensus, and that open port was treated as readiness. The
  TUN and the four-step self-test therefore ran against a Tor still at
  "Bootstrapped 30%", and the session was torn down as a failure at 66% while Tor
  was working and simply needed another minute. `TorTransport.start()` now waits for
  `Bootstrapped 100%`, reports the percentage as it climbs, and allows 300 s.
- **Psiphon never reported Connected.** Three separate causes:
  - A pinned exit country is a hard filter in psiphon-tunnel-core. The transport now
    retries with an automatic exit (and a fresh datastore) instead of hunting for an
    egress that will never appear.
  - The service aborted the session unless Psiphon bound exactly port 1819
    (`check(port == SOCKS_PORT)`). It now follows the port Psiphon reports through
    `onListeningSocksProxyPort`, and only warns when that differs.
  - The external path did not wait for the previous session's listener to be
    released before starting, so a leftover socket pushed Psiphon onto a random port.
    It now waits, exactly like the Aether path already did.
- The self-test grace window for Psiphon, Tor and chained sessions is 150 s instead
  of 90 s. Even a fully bootstrapped Tor needs seconds to build its first exit
  circuit, and a chained session pays both hops' warm-up.
- Psiphon's datastore moved out of `filesDir` root into `filesDir/psiphon`, so a
  wedged datastore can be reset without touching `hev.yaml` or the Tor directory.
- Proxy mode and LAN sharing now relay into the *finished* pipeline's SOCKS port.
  Previously `ShareBridge` was hardcoded to 1819, which in a chained session would
  have quietly shared the first hop's exit.

### Architecture
- External transports own only their upstream SOCKS endpoint and report the port they
  actually bound. AetherVpnService remains the single owner of VPN consent, TUN,
  split tunneling, kill switch, diagnostics, forwarding, notifications, and teardown.
- `TransportBackend` gained `AETHER_PSIPHON` and `AETHER_TOR` plus the
  `usesAetherEngine` / `usesExternal` / `isChained` predicates, so the service and
  the UI branch on capability rather than on an enum identity check.
- Both hops of a chained session are supervised; a dead stage 1 rebuilds the session
  instead of leaving a Connected badge over a proxy that cannot dial.
- Profile DataStore and Intent codec persist backend and exit-region selections by
  NAME, so the two appended enum values are backward compatible with saved profiles.

### Versioning
- Version name: 1.2.7 (unchanged)
- Version code: 11 (split outputs derive monotonic ABI-specific codes from this base).

## 1.2.7 — fix pass (r2): Psiphon media stalls + security audit pass 3

Version unchanged (1.2.7 / versionCode 11). No new UI, no new permissions.

### Fixed — `Aether + Psiphon` collapsed during video playback
Root cause was four compounding defects, all reproduced from the field log
(`docs/PSIPHON_MEDIA_STALL.md` has the annotated timeline):

- **QUIC is no longer carried over udpgw.** A video stream on UDP/443 shared one TCP port forward
  with every DNS query on the device: head-of-line blocking plus a TCP-over-TCP congestion
  meltdown. Dropping UDP/443 from the first datagram makes browsers fall back to HTTP/2 over TCP,
  where each flow gets its own Psiphon channel. DNS, VoIP and every other UDP protocol are
  untouched.
- **The udpgw writer can no longer block the UDP pump.** Frames were written straight from the
  datagram pump under one lock with a blocking write, so a congested tunnel stopped UDP for the
  whole device, DNS included. There is now a bounded, prioritised queue (DNS has its own lane), a
  dedicated writer thread, batched flushes, and oldest-first drop for bulk frames.
- **IPv6 flows fail instantly instead of dying at the exit.** The TUN offers IPv6 while Psiphon
  exits are IPv4-only, so a video player's fan-out produced 26 refused port forwards in 4.2 s. The
  front now latches the verdict after two probes and answers IPv6 locally with `host unreachable`.
  Leak protection is unchanged.
- **The health watchdog no longer rotates on load.** "25 refused port forwards in 45 s" measured how
  busy the session was, not whether the server filtered, and it tore a healthy tunnel down 4 s into
  a video, three times in two minutes. The tunnel's own counter is now corroborating only: a
  rotation also requires refusals for 3 distinct destinations, and structural refusals (IPv6, the
  udpgw intercept, TCP/53) are no longer reported as censorship.

Also: DNS falls back to **DNS-over-HTTPS on 443** when a server refuses TCP/53 (which was burning
one refused port forward per lookup); udpgw connection ids can no longer collide and cross-deliver
another flow's replies; the `conids` map is bounded; `hev.yaml` gets `connect-timeout: 12000`,
`udp-read-write-timeout: 60000` and `limit-nofile: 65535`; and the latency badge finally measures
the port the finished pipeline exposes instead of stage 1's listener.

### Security (audit pass 3 — `docs/SECURITY_AUDIT_1.2.7-r2.md`)
- **CRITICAL, reported not silently changed:** the release signing key is committed to the repo
  (`.github/ci-keystore.jks.b64`, password in `app/build.gradle.kts`). Anyone can sign an APK that
  installs over this app as a legitimate update. Needs a key rotation; release builds using that
  key now print a loud warning.
- The exit-IP badge no longer comes from cleartext HTTP first — an on-path attacker could forge
  "you are protected". The authenticated provider is tried first.
- The persisted diagnostics log no longer records per-flow destinations (partial browsing history)
  or the engine's argv values (Zero Trust team name, pinned gateways, resolvers, routing rules).
- Unsynchronised iteration of a shared flow map fixed.

## 1.2.8-r5

- **Root cause of the r4 round: the r4 build was never tested.** Five independent strings in the field log belong to the r3 engine and Kotlin. See docs/LIVE_STREAM_STALL_1.2.8-r5.md.
- **Build identity added end to end.** PATCHLEVEL -> build.rs stamp inside libaether.so -> verified by build-natives.sh on the stripped .so -> verified again by CI inside the packaged APK -> cross-checked at runtime by BuildProvenance -> shown in the About card. versionName stays 1.2.8.
- **CI could no longer silently revert engine patches.** sync-core.sh discovers patched files from AETHER-APP-PATCH markers instead of a hand-written list (which omitted Cargo.toml, netstack.rs, sysprofile.rs and build.rs), and a patch that cannot be rebased now fails the build.
- **Netstack device backpressure (root cause #2).** StackDevice::transmit() returned Some() unconditionally over an unbounded queue, so every drop happened after smoltcp had recorded the packet as sent and was invisible to the congestion controller r4 enabled. transmit() now refuses at MAX_DEVICE_TX and flush_tx holds bursts instead of shredding one packet per pass.
- **MAX_BACKLOG_BYTES 8 MB -> 512 KB**; **first netstack telemetry line at 2 s** with device queue peak and backpressure counters.
- **Hard RTT floor on endpoint selection**: a discarded cached endpoint is kept when the scan fails to beat it, so rejecting a 396 ms cache can no longer land on a 475 ms replacement.
