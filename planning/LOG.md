---
title: Log
tags: [bitchatkmp, planning, log]
---

# Log

Append only. Newest at the bottom. Template in `README.md`.

## 2026-10-12, cloud-claude

- Did: reviewed the architecture (four read-only review agents and a planning agent), then the owner reviewed the findings and decided D1 to D12. Implemented the first slice on `claude/naming-rule-and-safety-fixes`: seven commits, listed in `STATUS.md`.
- Corrected after the owner's review: "navigation in the domain" is withdrawn; `LocationRepo` and the Tor gate is not a leak; the discarded `verifyPasswordOwnership` result is not a bug; `ObserveMeshChannelPeople` already rethrew cancellation. Details at the top of `reference/2026-10-12-clean-architecture-review.md`.
- Pushed: `claude/naming-rule-and-safety-fixes` at `23dad85`, and this branch.
- Needs: the local agents to build, review with Codex and device-test the Apple dispatcher change (`STATUS.md`, Asks); the owner to answer Q1 to Q5.

## 2026-10-12, cloud-claude (later)

- Did: tried to delete `claude/clean-architecture-review-b6fcfl` as the owner asked (D13). GitHub refused with HTTP 403: this cloud session may push branches but not delete them.
- Needs: the owner, or a local agent with the owner's say-so, to delete it (`git push origin --delete claude/clean-architecture-review-b6fcfl`, or the "Delete branch" button on fluxxion82/bitchatKmp#1).

## 2026-10-12, local-claude

- Did: reviewed `claude/naming-rule-and-safety-fixes` at `23dad85` and the documents here, with two Codex (`gpt-6-astra`) reviews, then a second Codex round on the result ("nothing blocking"). Merged the branch onto the owner's `main`, which had four commits you could not see. Fixed what did not compile (`SettingsViewModel`, a test of `main`'s) and the desktop Koin `verify()`. Implemented Q3 and Q4. Took the Apple dispatcher commit out into `claude/apple-io-off-main`. Squash-merged the rest into `main` as `e4e5d94` (on the owner's machine; not pushed yet). Everything found, with evidence: `reference/2026-10-12-local-review.md`.
- Changed here: `CONVENTIONS.md` is new (the project's rules; you cannot see the owner's local `CLAUDE.md`). `README.md`: only `planning/` counts on this branch, questions live in `STATUS.md`. `DECISIONS.md`: D14 to D16 from the owner (short commit messages without trailers, few comments, squash merges). Notes at the top of the review and the plan. `STATUS.md` rewritten.
- Built and run: see `STATUS.md`.
- For cloud-claude, about the first slice: commit messages were long and carried two trailers each; D14 says one short line. Questions and "verified here" notes belong in `STATUS.md` and `LOG.md`. A property that shares a name with a function of the same class is not a rename you can check without compiling the module: say so when a change reaches past `:domain`.
- Needs: the owner to test `claude/apple-io-off-main` on an iPhone and to answer Q1, Q2 and Q5. cloud-claude to read `CONVENTIONS.md` and the local review, start from `main`, and propose the next slice here before writing code.

## 2026-10-12, local-claude (later)

- Did: on the owner's word pushed `main` (`e4e5d94`) and deleted `claude/naming-rule-and-safety-fixes` and `claude/clean-architecture-review-b6fcfl`. The owner answered Q1 to Q5: D17 to D20.
- Doing: D18 and D20 on a local branch; an entry here when they are on `main`.
- Needs: cloud-claude to start from `main`, read `CONVENTIONS.md` and the local review, and propose the next slice here first. Leave the files named in `STATUS.md` alone until then.
