---
title: Planning branch
tags: [bitchatkmp, planning, protocol]
---

# Planning branch

Only the `planning/` directory of this branch matters. It is where the agents working on bitchatKmp leave each other work, answers and questions. The rest of the tree is the source as it was when the branch was cut (`389fe0a`) and goes stale: never read code from here, and never merge this branch into `main` or `main` into it.

## Who writes here

- **cloud-claude:** Claude Code in a claude.ai cloud session. It cannot reach OpenAI, Google Maven or a device, so it cannot run Codex, build modules that apply the Android Gradle Plugin, or test on iOS or Android. In practice it builds and tests `:domain` only. It sees `origin` only: a commit the owner has not pushed does not exist for it.
- **local agents:** Claude Code and Codex on the owner's machine. They can build every module and run devices.
- **owner** (fluxxion82): decides. A decision the owner makes in chat wins over anything written here; record it in `DECISIONS.md`.

## Before doing anything

1. Fetch: `git fetch origin planning`. Read every file from that one commit, either in a checkout fast-forwarded to it or in place with `git show origin/planning:planning/<file>`.
2. Read `STATUS.md`, then the newest entries at the bottom of `LOG.md`.
3. Check `DECISIONS.md` before proposing anything it already settles, and `CONVENTIONS.md` before writing code or a commit.

## Files

| File | What it is | How to change it |
|---|---|---|
| `STATUS.md` | What is in flight, what each branch waits on, open questions | Edit in place; keep it current |
| `DECISIONS.md` | The owner's decisions | Append only; only the owner decides |
| `LOG.md` | Messages between agents | Append only, newest at the bottom, one entry per working session |
| `CONVENTIONS.md` | The project's working rules: architecture, commits, comments, verification, what a refactoring must not break | Edit in place when the owner changes a rule |
| `reference/` | The architecture review, the plan, the use-case inventory | Snapshots; corrections go in the note at the top of each |

## Rules

- Code goes on code branches, never on `planning`. Planning documents never go on code branches.
- A question goes in `STATUS.md` under Open questions with an ID (Q1, Q2, …) and the branch and commit it is about. Commit messages only describe the change (D14).
- To answer a question, write the answer under it in `STATUS.md`, starting with who answered and the date, and add a `LOG.md` entry. It moves to Answered once the owner confirms.
- When you push to a code branch for another agent to look at, add a `LOG.md` entry naming the branch and commit, and what you built and ran at that commit.
- Dates are UTC, `YYYY-MM-DD`. Name yourself in every `LOG.md` entry.
- Keep entries short. Point at files and commits rather than pasting code.

## LOG entry template

```markdown
## YYYY-MM-DD, <who>

- Did: what changed, and on which branch.
- Pushed: `<branch>` at `<sha>`.
- Needs: who should do what next.
```
