# Build validation

- Source structure and Kotlin callback signatures were checked against the embedded
  Psiphon AAR (`PsiphonTunnel.HostService`). All fifteen interface methods are
  implemented; only the bodies of `onConnecting`, `onConnected`, `onExiting`,
  `onListeningSocksProxyPort` and `onAvailableEgressRegions` changed.
- Version is 1.2.7 / base versionCode 11. Deliberately NOT bumped in this revision.
- Every `when` over `TransportBackend` and `ExternalKind` was re-checked for
  exhaustiveness after `AETHER_TOR` and `ExternalKind.TOR` were REMOVED
  (`ui/AdvancedPanel.kt` backendLabel + backendHelp + the exit-country helper,
  `model/TransportBackend.kt` externalKind + pipelineLabel,
  `transport/ExternalTransport.kt` factory). The service and the UI branch on the
  capability predicates instead of on enum identity.
- Both profile persistence paths (DataStore and Intent codec) store the backend by
  NAME, so the appended enum values decode old saved profiles unchanged.
- Brace/paren balance and import usage were verified on every touched file.
- No native Tor step remains in release CI: this revision removed the backend, the two
  Kotlin files behind it and `scripts/build-tor.sh`. The Aether core and the
  Psiphon AAR are the only native inputs left.
- `.github/removed-sources.txt` lists `transport/TorTransport.kt` and
  `transport/TorSocksFront.kt`, so a build over an older checkout purges the
  orphans instead of compiling against symbols that no longer exist.
- The UI work was re-checked the same way: `ui/components/GlowCycle.kt` is
  new and owns the shared band drawing, the duplicate `TWO_PI` constant was
  removed from `ConnectionCard.kt` (a private top-level const would have clashed
  with the shared one in the same package), and every new string
  (`meta_ping_strength`, `ping_quality_*`) exists in both `values/` and
  `values-fa/`.
- A local Gradle compile was not run in the packaging environment: the input archive
  omits `gradlew` and `gradle-wrapper.jar`, and no Android SDK is available there.
  CI remains the authoritative full Android build.

## 1.2.7 stability + localisation revision

- Version deliberately unchanged: still 1.2.7 / base versionCode 11.
- `PsiphonTunnel.HostService` is still fully implemented; only the bodies of
  `onConnected`, `onListeningSocksProxyPort` and `onAvailableEgressRegions` changed,
  and no interface method was added or removed.
- `PsiphonHealth.bind` now hands the rotation callback a `(Rotation, Target)` pair.
  `PsiphonTransport` is the only caller and was updated with it; `Rotation` is still
  exhaustive over `RECONNECT`/`RESTART` in the one `when` that switches on it.
- New Psiphon config keys (`DisableReplay`,
  `EstablishTunnelServerAffinityGracePeriodMilliseconds`) are only emitted after a
  server has been convicted of filtering, and both exist in the embedded
  `psiphontunnel-2.0.39` Go library; unknown keys are ignored by the Go side, so an
  older core degrades to today's behaviour rather than failing to start.
- Region steering may only pick a code the library reported through
  `onAvailableEgressRegions`, because `EgressRegion` is a hard filter and steering
  into an empty region would hang establishment instead of fixing anything.
- `PsiphonSocksFront.retarget()` takes the object monitor and then `udpgwLock`, the
  same order as `start`, `stop` and `onServerRotated`, so no new lock cycle exists.
- RTL: `AetherTheme` is the single root of both Compose entry points
  (`MainActivity`, `CrashReportActivity`), so pinning `LocalLayoutDirection` there
  covers every screen, and `Dialog`/`Popup`/`DropdownMenu`/`ModalBottomSheet` inherit
  it through the same composition local.
- Typography: all fifteen Material 3 roles are re-cut, which is what Material's own
  components read; `FontFamily.Monospace` call sites (timers, transfer rates,
  IP:port, diagnostics log, crash trace) are intentionally left alone so numeric
  columns keep aligning.
- `res/font/vazirmatn_bold.ttf` is a static Bold face (no `fvar` table), so every
  `FontWeight` entry maps to it explicitly to avoid synthetic emboldening.
- Brace/paren balance and import usage re-verified on every touched file.
- A local Gradle compile was again not run: the packaging environment has no Android
  SDK, no Gradle wrapper and no network access. CI remains the authoritative build.
