import { existsSync, readFileSync } from "node:fs";
import { isAbsolute, resolve, win32 } from "node:path";
import type { Assertion } from "./types.js";

export interface ExecutionResult {
  stdout: string;
  stderr: string;
  exitCode: number | null;
  cwd: string;
}

export function checkAssertion(assertion: Assertion, result: ExecutionResult): string | null {
  switch (assertion.type) {
    case "exit_code":
      if (result.exitCode !== assertion.value) {
        return `expected exit code ${assertion.value}, got ${result.exitCode}`;
      }
      return null;

    case "stdout_equals":
      if (result.stdout !== assertion.value) {
        return `stdout did not match exactly\n--- expected ---\n${assertion.value}--- actual ---\n${result.stdout}`;
      }
      return null;

    case "stdout_contains":
      if (!result.stdout.includes(assertion.value)) {
        return `stdout did not contain ${JSON.stringify(assertion.value)}\n--- actual stdout ---\n${result.stdout}`;
      }
      return null;

    case "stdout_not_contains":
      if (result.stdout.includes(assertion.value)) {
        return `stdout unexpectedly contained ${JSON.stringify(assertion.value)}`;
      }
      return null;

    case "stdout_matches": {
      const re = new RegExp(assertion.pattern, "m");
      if (!re.test(result.stdout)) {
        return `stdout did not match /${assertion.pattern}/\n--- actual stdout ---\n${result.stdout}`;
      }
      return null;
    }

    case "stderr_equals":
      if (result.stderr !== assertion.value) {
        return `stderr did not match exactly\n--- expected ---\n${assertion.value}--- actual ---\n${result.stderr}`;
      }
      return null;

    case "stderr_contains":
      if (!result.stderr.includes(assertion.value)) {
        return `stderr did not contain ${JSON.stringify(assertion.value)}\n--- actual stderr ---\n${result.stderr}`;
      }
      return null;

    case "file_exists":
      if (!existsSync(sandboxPath(result.cwd, assertion.path))) {
        return `expected file to exist: ${assertion.path}`;
      }
      return null;

    case "file_not_exists":
      if (existsSync(sandboxPath(result.cwd, assertion.path))) {
        return `expected file to NOT exist: ${assertion.path}`;
      }
      return null;

    case "file_content": {
      const fp = sandboxPath(result.cwd, assertion.path);
      if (!existsSync(fp)) return `file_content: file not found: ${assertion.path}`;
      const content = readFileSync(fp, "utf8");
      if (assertion.equals !== undefined && content !== assertion.equals) {
        return `${assertion.path} contents did not match\n--- expected ---\n${assertion.equals}--- actual ---\n${content}`;
      }
      if (assertion.contains !== undefined && !content.includes(assertion.contains)) {
        return `${assertion.path} did not contain ${JSON.stringify(assertion.contains)}`;
      }
      return null;
    }

    default:
      throw new Error(`unknown assertion type: ${(assertion as { type: string }).type}`);
  }
}

function sandboxPath(root: string, path: string): string {
  const parts = path.replaceAll("\\", "/").split("/");
  if (path.length === 0 || isAbsolute(path) || win32.isAbsolute(path) || parts.includes("..")) {
    throw new Error(`path must stay within the test sandbox: ${path}`);
  }
  return resolve(root, path);
}
