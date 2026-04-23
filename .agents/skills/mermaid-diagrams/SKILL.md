---
name: mermaid-diagrams
description: Create, update, and review Mermaid diagrams embedded in Markdown or documentation. Use when Codex needs to add or fix Mermaid syntax, choose an appropriate Mermaid diagram type, keep labels readable, or document Mermaid-specific constraints such as line breaks, node text, layout limits, and state-machine formatting.
---

# Mermaid Diagrams

Use Mermaid that is valid, readable, and easy to maintain in repo docs.

## Core rules

- Prefer Mermaid diagrams only when the structure is clearer than prose or tables.
- Match the diagram type to the problem:
  - `stateDiagram-v2` for state machines
  - `flowchart TD` or `flowchart LR` for flows and decision paths
  - `sequenceDiagram` for message exchanges
  - `classDiagram` or `erDiagram` only when structure is the main point
- Keep labels short enough to remain readable in common Markdown renderers.
- Use `<br>` for line breaks inside Mermaid labels.
- Do not use `\n` as a Mermaid label newline; it is not a valid rendered newline for this repo's guidance.
- Whenever a document describes a state change, state machine, lifecycle, or transition logic, add a Mermaid diagram unless the change is truly trivial.
- Keep node IDs simple and ASCII-only.
- Prefer stable wording over decorative wording.

## Authoring workflow

1. Choose the smallest Mermaid diagram type that expresses the idea clearly.
2. Draft the diagram with concise labels first.
3. Replace any attempted label newline escapes with `<br>`.
4. Check for renderer-width problems:
   - shorten labels
   - move detail into notes outside the diagram
   - split one crowded diagram into two smaller ones
   - prefer `<br>` over long wrapped transition text
5. Keep threshold values, enum names, and transition conditions exactly aligned with the source text or implementation.
6. If the Mermaid block documents implemented behavior, update the surrounding prose to match the same source of truth.

## Label and layout guidance

- Prefer `Label: value` or short condition phrases over long sentences inside nodes.
- For multi-condition transitions, use `<br>` between conditions when needed.
- Avoid very wide single-line edge labels.
- If a diagram becomes hard to read, reduce in-diagram text and move explanation below the block.
- When documenting enums, keep the canonical enum spelling in the surrounding prose and use short display forms only where they add value.

## State-machine guidance

- Use `stateDiagram-v2` for controller, battery, and pair lifecycle behavior.
- Add a Mermaid state diagram whenever state transitions are part of the documented behavior.
- Keep transitions directional and explicit.
- Put trigger thresholds directly on transitions when they are part of the rule.
- Add implementation notes below the diagram when firmware behavior is only partially implemented.

## Broad maintenance rule

- If new Mermaid conventions are discovered for this repo, add them to [references/mermaid-guidance.md](references/mermaid-guidance.md) and keep this file focused on the workflow and non-obvious rules.
