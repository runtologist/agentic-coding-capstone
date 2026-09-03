# Task Packet Template (parallel subagents)

Copy one packet per subagent. Each packet must be self-contained: a subagent
must never need to read another packet to do its job.

---

## Packet: <ID> — <short slug>

**Branch:** `task/<ID>-<slug>` (worktree-isolated, one writer)

### Goal
<One paragraph: what this packet delivers and why it matters.>

### Contract files (READ-ONLY for this packet)
- `Model.scala` / error ADT / shared interfaces frozen in Phase A.
- Relevant SPEC.md sections: <§x.y>
- If the packet needs a contract change: STOP and report back; do not edit.

### Files this packet may write (exclusive ownership)
- `src/main/scala/<pkg>/<Module>.scala`
- `src/test/scala/<pkg>/<Module>Spec.scala`

### Out of scope
- <Explicit list of what NOT to touch, including other modules' files,
  build.sbt, CI scripts, and the provided test harness.>

### Behavior requirements (verbatim from SPEC)
- <Exact output formats, error messages, edge cases.>

### Definition of done
1. `sbt --client compile` clean (no new warnings).
2. `sbt --client test` green, including new unit tests for this module.
3. `sbt --client scalafmtCheckAll` green.
4. Evidence: paste command + output tail into the packet report.

### Report format (required output of the subagent)
- Changed files (list)
- Tests added (list)
- Commands run + results
- Deviations from contract, if any (must be justified)
- Risks / open questions

---

## Integration rules (integrator agent)

1. Merge packets one at a time into `develop`; run the full gate suite after
   EACH merge, never batch merges.
2. If a packet conflicts with a contract file, stop and re-plan.
3. Reviewer packets are read-only: they may only add a report file under
   `docs/reviews/`.
