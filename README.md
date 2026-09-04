# Scala Capstone Workspace

![CI](https://github.com/runtologist/agentic-coding-capstone/actions/workflows/capstone-ci.yml/badge.svg)

Generated Scala implementations of the workshop capstone projects, built by
AI coding agents following a single reusable playbook.

## Approach

The core artifact of this repository is a generic delivery plan,
[`docs/GENERIC_CAPSTONE_PLAN.md`](docs/GENERIC_CAPSTONE_PLAN.md). It defines
the non-negotiable quality gates, the phased subagent workflow (intake →
architecture derivation → parallel implementation → integration → adversarial
review → fix rounds), the git workflow, and lessons learned from previous
capstones.

The plan was **derived and validated on TabbyShell**, the workshop's practice
capstone. Each subsequent capstone is executed by instantiating the same plan
against its own `SPEC.md` and acceptance tests.

## Layout

```text
capstone-scala/
  .github/workflows/   # CI: sbt gate suite + vendored acceptance harness per project
  docs/                # Generic plan + per-capstone records
    GENERIC_CAPSTONE_PLAN.md  # the playbook every capstone follows
    TASK_PACKET_TEMPLATE.md   # task-packet template for implementation subagents
    tabbyshell/               # TabbyShell contract, task packets, ledger, findings
    examples/                 # architecture case studies (e.g. TabbyShell)
  harness/             # Vendored, unmodified workshop acceptance harnesses
    tabbyshell/        #   harness for TabbyShell (more added per capstone)
  <capstone>/          # One Scala project per capstone (tabbyshell, snap, ...)
```

## Current status

| Capstone              | Status                          | Evidence                                        |
| --------------------- | ------------------------------- | ----------------------------------------------- |
| TabbyShell (practice) | done                            | 50/50 acceptance cases, 411 unit tests, 91.9% statement coverage — see [`docs/tabbyshell/LEDGER.md`](docs/tabbyshell/LEDGER.md) |
| Snap                  | pending — spec not yet released | —                                                |

## Environment

Requires Java 25 LTS and sbt 1.10.x on `PATH`. Locally I use a gitignored
`scripts/env.sh` that points `JAVA_HOME` at the Homebrew OpenJDK 25 install;
CI installs Java/sbt itself.

## Building and verifying

Full workflow, quality gates, and ground rules live in the
[plan](docs/GENERIC_CAPSTONE_PLAN.md). Per-project quick reference:

```bash
cd <capstone>
sbt --client test assembly   # unit gates
# acceptance suite: bash harness/<capstone>/run_tests --lang scala \
#   --implementation-root $PWD/<capstone>
```

## Continuous Integration

`.github/workflows/capstone-ci.yml` auto-discovers every top-level directory
containing a `build.sbt` and, for each project, runs:

1. `sbt scalafmtCheckAll; test; assembly` — formatting gate, unit tests, fat
   jar.
2. The vendored acceptance harness at `harness/<project>/run_tests` (skipped
   until a harness is vendored for that project).

Test counts are summarized on the run's **Summary** page and full logs are
uploaded as artifacts. The harness step fails the build unless every vendored
test case passes. Adding a new capstone only requires `<name>/build.sbt` and
`harness/<name>/`; the workflow needs no changes.
