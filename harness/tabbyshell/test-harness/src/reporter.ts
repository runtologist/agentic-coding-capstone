import type { TestResult } from "./types.js";

const RED = "\x1b[31m";
const GREEN = "\x1b[32m";
const YELLOW = "\x1b[33m";
const DIM = "\x1b[2m";
const BOLD = "\x1b[1m";
const RESET = "\x1b[0m";

const noColor = process.env.NO_COLOR !== undefined;
const c = (color: string, s: string) => (noColor ? s : `${color}${s}${RESET}`);

export function reportResult(result: TestResult, verbose: boolean): void {
  const status = result.passed
    ? c(GREEN, "✓")
    : c(RED, "✗");
  const time = c(DIM, `${result.durationMs}ms`);
  process.stdout.write(`  ${status} ${result.name} ${time}\n`);
  if (!result.passed) {
    for (const failure of result.failures) {
      const lines = failure.split("\n");
      for (const line of lines) {
        process.stdout.write(`      ${c(RED, line)}\n`);
      }
    }
    if (verbose) {
      process.stdout.write(c(DIM, `      [stdout]\n`));
      for (const line of result.stdout.split("\n")) {
        process.stdout.write(c(DIM, `      | ${line}\n`));
      }
      process.stdout.write(c(DIM, `      [stderr]\n`));
      for (const line of result.stderr.split("\n")) {
        process.stdout.write(c(DIM, `      | ${line}\n`));
      }
      process.stdout.write(c(DIM, `      [exit ${result.exitCode}]\n`));
    }
  }
}

export function reportSummary(results: TestResult[]): void {
  const passed = results.filter((r) => r.passed).length;
  const failed = results.length - passed;
  const totalMs = results.reduce((acc, r) => acc + r.durationMs, 0);
  process.stdout.write("\n");
  if (failed === 0) {
    process.stdout.write(c(BOLD, c(GREEN, `${passed} passed`)) + c(DIM, ` in ${totalMs}ms\n`));
  } else {
    process.stdout.write(
      c(BOLD, c(RED, `${failed} failed`)) +
      `, ${c(GREEN, `${passed} passed`)}` +
      c(DIM, ` in ${totalMs}ms\n`),
    );
  }
}
