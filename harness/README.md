# Vendored acceptance harnesses

Unmodified snapshots of the workshop capstone harnesses
(`capstones/<slug>/` from the vibe-coding-2 workshop materials), vendored here
so CI can run the generic acceptance test suites inside this repository.

- `tabbyshell/` — verifier, Node test-harness, YAML cases, fixtures, SPEC.md

**Do not edit these files.** To update, re-copy the corresponding
`capstones/<slug>/` directory from the workshop materials (excluding
`node_modules/`), commit as a single "vendor:" commit, and note the source
revision/date in the commit message.

CI runs each harness via
`harness/<project>/run_tests --lang scala --implementation-root <project>`
after `sbt test assembly`. The matching implementation for `<project>` is the
top-level directory of the same name containing a `build.sbt`.
