# Scala Capstone Workspace

This repository contains generated Scala code, planning documents, and scripts
for the workshop capstone. The intended capstone is **Snap**. TabbyShell is
used as a practice project to validate the toolchain, harness interface,
generic plan, and parallel-subagent workflow.

## Layout

```text
capstone-scala/
  docs/                     # Generic plan, contracts, findings, task templates
  scripts/                  # Environment and verification helper scripts
  tabbyshell/               # Practice Scala implementation for TabbyShell
  snap/                     # Future Snap implementation, created when spec is available
```

## Environment

Use the latest LTS Java installed via Homebrew:

```bash
source scripts/env.sh
java -version
```

Expected: OpenJDK 25.x LTS.

## TabbyShell practice verification

Build the Scala assembly and run a harness smoke test:

```bash
source scripts/env.sh
cd tabbyshell
sbt --client test assembly
./scripts/smoke-tabbyshell.sh
```

Full TabbyShell verification, once implementation is complete:

```bash
./scripts/verify-tabbyshell.sh
```

## Rules

- The project specification and provided acceptance tests are the contract.
- Do not modify provided workshop test harnesses unless explicitly instructed.
- Every meaningful change must produce automated evidence: compile, tests,
  assembly, and acceptance-harness output.
- Parallel subagents work in separate git worktrees and must not edit the same
  files concurrently.
- Keep side effects explicit and boundary-parsed; prefer ADTs and typed errors.
