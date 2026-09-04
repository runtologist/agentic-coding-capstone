# Final Spec-Compliance Review (post-fix-round, main @ 6eb616e)

**Reviewer:** fresh-context reviewer (run 2d247a4e)
**Verdict:** OK — no blockers, no majors. One minor (TTY per-stream, ruled as documented platform limitation).

## Verified correct

- Exit-code contract (0/1/2), ReexecCode 254 internal-only
- All pinned error messages match CONTRACT.md exactly
- Deterministic ordering (utf8Compare) throughout
- HEAD Content-Length parity with GET (E4-P2 fix verified)
- Lazy re-exec: no output before re-exec, guard prevents loops, signal forwarding correct
- Strict UTF-8 decode (CodingErrorAction.REPORT) on repo, config, HTTP bodies
- No mutation on failed validation
- Serve snapshot immutability
- Diff algorithm: correct §5 recurrence, delete-on-tie, coalescing, blocked linear-space
- OT transform (§6.3): Q-insert priority, count splitting, coalescing correct
- Replay/merge (§6.1–6.5): canonical order, namespace resolution, path rules 1–6, warning dedup/sort
- JSON serialization: byte-identical to JSON.stringify(value, null, 2) + "\n"
- CLI grammar: all 24 test cases correct
- Config precedence: local-first, no fallback on local error, HOME absent → unavailable not error

## Single finding (minor, ruled)

**TTY per-stream detection** (E1-S2 / E2-F1 / E5-F2)
- `System.console()` cannot distinguish stdout vs stderr TTY-ness
- `resolvePresentation` itself is correctly parameterised and unit-tested for all four combinations
- JVM platform limitation; no standard API without JNI/JLine
- Harness never exercises TTY mode (pipes both streams)
- **Ruling:** Documented waiver. No code change required.

## Merge verdict: OK
