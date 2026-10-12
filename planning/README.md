---
title: Planning branch
tags: [bitchatkmp, planning, protocol]
---

# Planning branch

This branch holds planning documents only. No code lives here, and nothing here is merged into `main`. It is where the agents working on bitchatKmp leave each other work, answers and questions.

## Who writes here

- **cloud-claude:** Claude Code in a claude.ai cloud session. It cannot reach OpenAI, Google Maven or a device, so it cannot run Codex, build modules that apply the Android Gradle Plugin, or test on iOS or Android.
- **local agents:** Claude Code and Codex on the owner's machine. They can build every module and run devices.
- **owner** (fluxxion82): decides. A decision the owner makes in chat wins over anything written here; record it in `DECISIONS.md`.

## Before doing anything

1. Fetch and read the latest: `git fetch origin planning`, then either check the branch out or read files in place with `git show origin/planning:planning/STATUS.md`.
2. Read `STATUS.md`, then the newest entries at the bottom of `LOG.md`.
3. Check `DECISIONS.md` before proposing anything it already settles.

## Files

| File | What it is | How to change it |
|---|---|---|
| `STATUS.md` | What is in flight, what each branch waits on, open questions | Edit in place; keep it current |
| `DECISIONS.md` | The owner's decisions | Append only; only the owner decides |
| `LOG.md` | Messages between agents | Append only, newest at the bottom, one entry per working session |
| `reference/` | The architecture review, the plan, the use-case inventory | Snapshots; corrections go in the note at the top of each |

## Rules

- Code goes on code branches, never on `planning`. Planning documents never go on code branches.
- A question about a specific commit goes in that commit's message (the owner's rule). Also list it in `STATUS.md` under Open questions with an ID (Q1, Q2, …) so the answer has a place to land.
- To answer a question, write the answer under it in `STATUS.md`, starting with who answered and the date, and add a `LOG.md` entry. It moves to Answered once the owner confirms.
- When you push to a code branch for another agent to look at, add a `LOG.md` entry naming the branch and commit.
- Dates are UTC, `YYYY-MM-DD`. Name yourself in every `LOG.md` entry.
- Keep entries short. Point at files and commits rather than pasting code.

## LOG entry template

```markdown
## YYYY-MM-DD, <who>

- Did: what changed, and on which branch.
- Pushed: `<branch>` at `<sha>`.
- Needs: who should do what next.
```
