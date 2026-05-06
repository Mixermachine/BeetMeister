---
name: github-pr-formatting
description: Format GitHub pull requests for this repo. Use when creating or editing a PR or MR body/title so the text is clear, structured, and easy to review.
---

# GitHub PR Formatting

- Use a short, factual title in imperative style.
- Structure the body with these sections in this order: `What Changed`, `Affected Areas`, `Validation`.
- Keep bullets flat. Do not use nested bullets unless a short two-item split is necessary.
- List affected areas using repo terms such as `app`, `firmware`, `docs`, `hardware`, and `firmware/tests/host`.
- Under `Validation`, list the exact commands that were run. Do not claim tests you did not run.
- Keep the PR focused on the changes being proposed. Do not describe local files or uncommitted work that are not part of the PR.
- Keep the body concise. Avoid long narrative status updates or debug history.
