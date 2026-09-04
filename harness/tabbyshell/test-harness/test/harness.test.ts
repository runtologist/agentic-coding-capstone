import assert from "node:assert/strict";
import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { checkAssertion, type ExecutionResult } from "../src/assertions.js";
import { runCase } from "../src/runner.js";
import type { TestCase } from "../src/types.js";
import { loadTestCases } from "../src/yaml-loader.js";

test("loader reports malformed YAML with its source path", () => {
  withTemp("loader", (root) => {
    const source = join(root, "broken.yaml");
    writeFileSync(source, "name: broken\nscript: [\n");
    assert.throws(() => loadTestCases(root), (error: Error) =>
      error.message.includes(source) && error.message.includes("invalid YAML"));
  });
});

test("assertions report success and useful failures", () => {
  const result: ExecutionResult = { stdout: "hello\n", stderr: "", exitCode: 7, cwd: tmpdir() };
  assert.equal(checkAssertion({ type: "exit_code", value: 7 }, result), null);
  assert.match(checkAssertion({ type: "stdout_contains", value: "missing" }, result)!, /did not contain.*missing/);
});

test("runner handles expected nonzero exits and candidate timeouts", async () => {
  await withCandidate(async (implementationRoot, projectRoot) => {
    const nonzero = await runCase(config(implementationRoot, projectRoot), aCase("process.exitCode = 7", 7));
    assert.equal(nonzero.passed, true, JSON.stringify(nonzero));

    const timeout = await runCase(config(implementationRoot, projectRoot), {
      ...aCase("setTimeout(() => {}, 10_000)", 0), timeout: 0.02,
    });
    assert.equal(timeout.passed, false);
    assert.match(timeout.failures.join("\n"), /expected exit code 0, got null/);
  });
});

test("runner removes its temporary sandbox", async () => {
  await withCandidate(async (implementationRoot, projectRoot) => {
    const result = await runCase(config(implementationRoot, projectRoot), aCase("process.stdout.write(process.cwd())", 0));
    assert.equal(result.passed, true, JSON.stringify(result));
    assert.equal(existsSync(result.stdout), false, `sandbox was retained: ${result.stdout}`);
  });
});

test("loader confines inline fixture and assertion paths to the sandbox", () => {
  withTemp("paths", (root) => {
    writeFileSync(join(root, "fixture.yaml"), `name: bad\nscript: ""\nfiles:\n  - {path: ../outside, content: x}\nassertions:\n  - {type: exit_code, value: 0}\n`);
    assert.throws(() => loadTestCases(root), /files\[0\]\.path: path must stay within the test sandbox/);
    rmSync(join(root, "fixture.yaml"));
    writeFileSync(join(root, "assertion.yaml"), `name: bad\nscript: ""\nassertions:\n  - {type: file_exists, path: /etc/passwd}\n`);
    assert.throws(() => loadTestCases(root), /assertions\[0\]\.path: path must stay within the test sandbox/);
  });
});

test("loader rejects unknown assertions and invalid assertion fields", () => {
  withTemp("assertion-schema", (root) => {
    writeFileSync(join(root, "unknown.yaml"), `name: bad\nscript: ""\nassertions:\n  - {type: stdout_contians, value: hello}\n`);
    assert.throws(() => loadTestCases(root), /unknown assertion type "stdout_contians"/);
    rmSync(join(root, "unknown.yaml"));
    writeFileSync(join(root, "field.yaml"), `name: bad\nscript: ""\nassertions:\n  - {type: exit_code, value: zero}\n`);
    assert.throws(() => loadTestCases(root), /value: expected an integer/);
  });
});

function aCase(script: string, exitCode: number): TestCase {
  return { name: "candidate", script, assertions: [{ type: "exit_code", value: exitCode }], timeout: 5 };
}

function config(implementationRoot: string, projectRoot: string) {
  return { lang: "ts" as const, implementationRoot, projectRoot };
}

async function withCandidate(run: (implementationRoot: string, projectRoot: string) => Promise<void>): Promise<void> {
  const root = mkdtempSync(join(tmpdir(), "tabbyshell-harness-candidate-"));
  const implementationRoot = join(root, "implementation");
  const sourceRoot = join(implementationRoot, "src");
  const { mkdirSync } = await import("node:fs");
  mkdirSync(sourceRoot, { recursive: true });
  writeFileSync(join(sourceRoot, "main.ts"), `let source = ""; process.stdin.setEncoding("utf8"); process.stdin.on("data", chunk => source += chunk); process.stdin.on("end", () => eval(source));`);
  try { await run(implementationRoot, root); }
  finally { rmSync(root, { recursive: true, force: true }); }
}

function withTemp(label: string, run: (root: string) => void): void {
  const root = mkdtempSync(join(tmpdir(), `tabbyshell-harness-${label}-`));
  try { run(root); } finally { rmSync(root, { recursive: true, force: true }); }
}
