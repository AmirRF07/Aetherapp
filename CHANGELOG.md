# Changelog

## 1.2.7

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
