# Packet L2 — Diff & OT engines

**Branch:** `task/03-diff-ot` (from `develop`)
**Depends on:** frozen `Model.scala` (tokens: `Model.tokenize`, `Model.detokenize`, `Model.isText`, `Model.isCanonicalTokenSeq`, `Model.applyEdit`), `SnapError.scala`. Do NOT modify Model/SnapError/Json; if a contract change is needed, STOP and report.

## Files owned (exclusive)
- `snap/src/main/scala/snap/Diff.scala`
- `snap/src/main/scala/snap/Ot.scala`
- `snap/src/test/scala/snap/DiffSpec.scala`
- `snap/src/test/scala/snap/OtSpec.scala`

## Safety rules (hard)
- Work ONLY under `snap/` in this checkout. No `rm -rf`/`mv` outside the repo; no edits outside owned files; no destructive git commands (no reset --hard, no branch -D on others, no push --force).
- Use `sbt --client`; run `sbt --client shutdown` after your final gate. Never interactive sbt tasks.

## Behavior requirements (from SPEC §5, §6.3; CONTRACT §10, §11)

### Diff.canonicalDiff(old: Vector[String], nw: Vector[String]): Vector[EditOp]
Token-level minimum-insert/delete edit script with this exact recurrence (SPEC §5):
- D(n,m)=0; D(i,m)=n-i; D(n,j)=m-j
- A[i]==B[j] → D(i,j)=D(i+1,j+1)
- else D(i,j)=1+min(D(i+1,j), D(i,j+1))
Walk from (0,0): equal tokens → retain 1; else choose delete 1 when D(i+1,j) <= D(i,j+1) (DELETE ON TIE), else insert [B[j]]; exhausted side → insert/delete remainder; coalesce adjacent same-kind ops.
Empty→empty yields empty script; empty-old yields one Insert of all new tokens; empty-new yields one Delete(n).

### Ot.transform(p: Vector[EditOp], q: Vector[EditOp]): Vector[EditOp]
Transform incoming edit P to apply after aggregate context edit Q (SPEC §6.3 table), processing both streams left→right, splitting counts:
- Q insert(len k) → emit retain(k), consume Q only — Q-insert row has PRIORITY (concurrent inserts integrate in canonical order)
- P insert → emit same insert, consume P only
- P retain / Q retain → emit retain(min), consume min both
- P delete / Q retain → emit delete(min), consume min both
- P retain / Q delete → emit nothing, consume min both
- P delete / Q delete → emit nothing, consume min both
Continue until both streams end (both consume the same base token count; trailing insert handled by its row). Coalesce adjacent output ops.

## Goldens to pin in unit tests (verbatim from YAML suite)
1. Test 05: old `a\nb\na\n` tokens ["a\n","b\n","a\n"] → new tokens ["b\n","a\n","a"] must yield `[delete 1, retain 2, insert ["a"]]` (deletion-on-tie rule). Rendered block:
```
--- a/repeated.txt
+++ b/repeated.txt
@@ -1,3 +1,3 @@
-a
 b
 a
+a
\ No newline at end of file
```
(rendering is L6's job; DiffSpec pins the EDIT SCRIPT, including the final token "a" without LF)
2. Test 22 OT matrix (base tokens ["0\n","1\n","2\n","3\n","4\n"]), merged results after transform+apply:
   - overlapping P-delete/Q-delete → `0\n3\n4\n`
   - split counts + insert priority + trailing insert → `A\n0\nB\n3\n4\nTAIL\n`
   - P-retain vs Q-delete → `0\n2\n3\n4\nA\n`
   - Q-insert before P-delete survives → `0\nB\n2\n3\n4\n`
   Derive exact P/Q scripts from harness/snap/tests/22-ot-matrix.yaml and assert transform output + applied result byte-exact.
3. Test 18 three-way: base ["start\n","end\n"]; a inserts "A\n" after start, b inserts "B\n" after start, c deletes "start\n". Verify convergence: canonical order c→b→a yields final "B\nA\nend\n" with NO warnings (replay integration is L3's job, but Ot.transform must support it — add a property-style unit test applying transforms pairwise).
4. Property tests: for random small token vectors, apply(old, canonicalDiff(old,new)) == new; canonicalDiff output always canonical (no adjacent same-kind, counts positive, insert tokens valid per Model.isValidInsertToken).

## Definition of done
1. Gates green: `source ../scripts/env.sh && cd snap && sbt --client shutdown; sbt --client "compile; test; scalafmtCheckAll"` then `sbt --client shutdown`.
2. ≥30 new focused tests across DiffSpec/OtSpec, all meaningful assertions.
3. Commit on branch, push: `git push -u origin task/03-diff-ot`. No Co-Authored-By trailers.
4. Report: files, test counts, gate tails, deviations, risks.
