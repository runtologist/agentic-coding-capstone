// Test case types — what capstones/tabbyshell/tests/*.yaml describes.

export type Lang = "ts" | "rust" | "scala";

export interface SetupStep {
  shell: string;
}

export interface CleanupStep {
  shell: string;
}

export type Assertion =
  | { type: "exit_code"; value: number }
  | { type: "stdout_equals"; value: string }
  | { type: "stdout_contains"; value: string }
  | { type: "stdout_not_contains"; value: string }
  | { type: "stdout_matches"; pattern: string }
  | { type: "stderr_equals"; value: string }
  | { type: "stderr_contains"; value: string }
  | { type: "file_exists"; path: string }
  | { type: "file_not_exists"; path: string }
  | { type: "file_content"; path: string; equals?: string; contains?: string };

export interface TestCase {
  name: string;
  description?: string;
  /** Frozen Unix timestamp passed via TABBY_NOW. */
  now?: number;
  /** Disable the AI external command path; failures route to plain-text fallback. */
  no_ai?: boolean;
  /** Files to write into the test cwd before running. */
  files?: { path: string; content: string }[];
  /** Shell setup commands run in the test cwd before running. */
  setup?: SetupStep[];
  /** Pipeline script to feed to `tabby --eval-file -` via stdin. */
  script: string;
  /** Optional command-line arguments to pass after `--eval-file -`. */
  extra_args?: string[];
  assertions: Assertion[];
  cleanup?: CleanupStep[];
  /** Test timeout in seconds. Default 30. */
  timeout?: number;
}

export interface TestResult {
  name: string;
  passed: boolean;
  failures: string[];
  durationMs: number;
  stdout: string;
  stderr: string;
  exitCode: number | null;
}
