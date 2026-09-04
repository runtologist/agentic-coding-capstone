import { readFileSync, readdirSync } from "node:fs";
import { isAbsolute, join, win32 } from "node:path";
import { parse } from "yaml";
import type { TestCase } from "./types.js";

export function loadTestCases(testsDir: string, filter?: string): TestCase[] {
  const cases: TestCase[] = [];
  const entries = readdirSync(testsDir).sort();
  for (const entry of entries) {
    if (!entry.endsWith(".yaml") && !entry.endsWith(".yml")) continue;
    if (filter && !entry.includes(filter)) continue;
    const source = join(testsDir, entry);
    const raw = readFileSync(source, "utf8");
    let parsed: unknown;
    try {
      parsed = parse(raw, { uniqueKeys: true });
    } catch (error) {
      throw new Error(`${source}: invalid YAML: ${(error as Error).message}`);
    }
    if (Array.isArray(parsed)) {
      for (const [index, value] of parsed.entries()) cases.push(validateCase(value, `${source}[${index}]`));
    } else {
      cases.push(validateCase(parsed, source));
    }
  }
  return cases;
}

function validateCase(value: unknown, location: string): TestCase {
  if (!isRecord(value)) throw new Error(`${location}: expected a test case mapping`);
  if (typeof value.name !== "string" || value.name.length === 0) throw new Error(`${location}.name: expected a non-empty string`);
  if (typeof value.script !== "string") throw new Error(`${location}.script: expected a string`);
  if (!Array.isArray(value.assertions) || value.assertions.length === 0) throw new Error(`${location}.assertions: expected a non-empty array`);
  if (value.files !== undefined) {
    if (!Array.isArray(value.files)) throw new Error(`${location}.files: expected an array`);
    for (const [index, fixture] of value.files.entries()) {
      if (!isRecord(fixture) || typeof fixture.path !== "string" || typeof fixture.content !== "string") {
        throw new Error(`${location}.files[${index}]: expected string path and content`);
      }
      confinedPath(fixture.path, `${location}.files[${index}].path`);
    }
  }
  for (const [index, assertion] of value.assertions.entries()) {
    if (!isRecord(assertion) || typeof assertion.type !== "string") {
      throw new Error(`${location}.assertions[${index}]: expected an assertion mapping with a type`);
    }
    validateAssertion(assertion, `${location}.assertions[${index}]`);
  }
  return value as unknown as TestCase;
}

function validateAssertion(assertion: Record<string, unknown>, location: string): void {
  const requireString = (field: string): string => {
    const value = assertion[field];
    if (typeof value !== "string") throw new Error(`${location}.${field}: expected a string`);
    return value;
  };

  switch (assertion.type) {
    case "exit_code":
      if (!Number.isInteger(assertion.value)) throw new Error(`${location}.value: expected an integer`);
      return;
    case "stdout_equals":
    case "stdout_contains":
    case "stdout_not_contains":
    case "stderr_equals":
    case "stderr_contains":
      requireString("value");
      return;
    case "stdout_matches": {
      const pattern = requireString("pattern");
      try {
        new RegExp(pattern, "m");
      } catch {
        throw new Error(`${location}.pattern: invalid regular expression`);
      }
      return;
    }
    case "file_exists":
    case "file_not_exists":
      confinedPath(requireString("path"), `${location}.path`);
      return;
    case "file_content":
      confinedPath(requireString("path"), `${location}.path`);
      if (assertion.equals !== undefined && typeof assertion.equals !== "string") {
        throw new Error(`${location}.equals: expected a string`);
      }
      if (assertion.contains !== undefined && typeof assertion.contains !== "string") {
        throw new Error(`${location}.contains: expected a string`);
      }
      return;
    default:
      throw new Error(`${location}.type: unknown assertion type ${JSON.stringify(assertion.type)}`);
  }
}

function confinedPath(path: string, location: string): void {
  const parts = path.replaceAll("\\", "/").split("/");
  if (path.length === 0 || isAbsolute(path) || win32.isAbsolute(path) || parts.includes("..")) {
    throw new Error(`${location}: path must stay within the test sandbox`);
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
