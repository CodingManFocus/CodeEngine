import type { Diagnostic } from "@codemirror/lint";
import { Text } from "@codemirror/state";
export interface Problem {
  line: number;
  message: string;
}
export function parseProblems(message: string, id: string): Problem[] {
  const problems: Problem[] = [];
  const pattern = new RegExp(`^(?:${id}\\.ce:|line )(\\d+):\\s*(.*)$`);
  for (const line of message.split("\n")) {
    const match = line.match(pattern);
    if (match) problems.push({ line: Number(match[1]), message: match[2] });
    else if (problems.length) problems.at(-1)!.message += "\n" + line;
  }
  return problems;
}
export function toDiagnostics(doc: Text, problems: Problem[]): Diagnostic[] {
  return problems
    .filter((problem) => problem.line >= 1 && problem.line <= doc.lines)
    .map((problem) => {
      const line = doc.line(problem.line);
      return {
        from: line.from,
        to: line.to,
        severity: "error",
        message: problem.message,
      };
    });
}
