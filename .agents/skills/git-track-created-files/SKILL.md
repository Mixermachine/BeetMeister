---
name: git-track-created-files
description: Automatically add newly created repository files to git tracking when they are intended to persist rather than act as temporary or generated artifacts. Use when Codex creates new source files, docs, tests, scripts, assets, or skills that should remain in the repo after the task.
---

# Git Track Created Files

When a task creates a real repository file, add it to git tracking in the same task instead of leaving it untracked.

## Core rule

- If you create a new file or directory that is intended to stay in the repo, run `git add` for it before finishing.
- Do not leave real deliverables as accidental untracked files.

## Apply this skill when

- creating new source files
- creating docs, tests, scripts, configs, assets, or skills
- creating new folders whose contents are part of the deliverable

## Do not apply this skill to

- temporary scratch files
- generated build outputs
- cache directories
- files the user explicitly wants left untracked

## Workflow

1. Create the intended repo files.
2. Decide whether each new file is a real deliverable or temporary output.
3. Add the real deliverables to git tracking with `git add`.
4. Check `git status --short` so new deliverables are not left as unexpected untracked files.

## Notes

- Prefer adding only the intended new files or directories, not unrelated broad paths.
- If the worktree already contains unrelated untracked files, avoid sweeping them in accidentally.
- This skill is about tracking new files, not committing them. Commit only when the user asks.
