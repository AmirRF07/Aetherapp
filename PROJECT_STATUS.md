# Final package status

- Version: 1.2.7 (unchanged)
- Base versionCode: 11
- Backends: Aether, Aether -> Psiphon (chained). The Tor backend and the chained
  Aether -> Tor mode were removed in this revision, together with the whole Tor
  runtime.
- Exit selection: Psiphon region filter with automatic fallback; flag emoji on
  every country row
- Loopback ports: engine SOCKS5 1819, chained second stage (Psiphon front) 1825,
  Psiphon's own listener 1827. Tor's former 1822/1823 are no longer bound.
- Architectures: arm64-v8a, armeabi-v7a
- Archive integrity: SOURCE_MANIFEST.sha256
- Security audit (1.2.7, UI pass 2): docs/SECURITY_AUDIT_1.2.7.md
