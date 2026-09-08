Pristine upstream copies of the files this app patches, at core 1.9.0.

Do not edit. scripts/sync-core.sh uses them as the merge base so the app's
engine patches can be rebased onto a new core instead of overwriting it.

1.2.9: every patched file now has a baseline here (1.2.8 cached only prober.rs
and wg_prober.rs, so the other eight had none and CI would have had to
reconstruct them from the AETHER-APP-PATCH markers - which is lossy wherever a
patch REPLACES an upstream line rather than adding to it).
