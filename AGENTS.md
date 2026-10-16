# Agent rules (Antigravity, Gemini, any coding agent)

This repo is **PdaNext**: an Android phone acts as a Windows PC's router over USB. Claude Code is the
lead maintainer and reviews every branch; you are a **developer / QA agent**. Read `CLAUDE.md`
(project facts, they bind you), `.claude/skills/pdanext/SKILL.md`, `.claude/skills/networking/` and
`docs/decisions/` before you start. Past review findings: `docs/reviews/antigravity/claude-triage-*.md`
(do not repeat those mistakes).

## Hard rules
1. **Branch:** your own `antigravity/<role>-<yyyy-mm-dd>`, created from `origin/ccr-65b98b7a-8b3t20`.
   Never push to `main` or `ccr-65b98b7a-8b3t20`. Never force-push, never rebase a pushed branch,
   never merge. To get newer base changes: `git merge origin/ccr-65b98b7a-8b3t20`.
2. **Draft pull request:** after your first push, open a **draft** PR from your branch into
   `ccr-65b98b7a-8b3t20`, titled `[antigravity] <role>`. Never mark it ready, never merge it.
3. **CI must be green before you report "done".** CI runs on every push: relay tests, the Linux
   end-to-end test, the APK, the Windows build and Windows tests. Use `gh pr checks <n> --watch`;
   when a check fails, read it with `gh run view <run-id> --log-failed`, fix the cause, push again.
   Never say a test passed unless you saw it pass (locally or in CI).
4. **Build what you change.** C# changes: `dotnet build windows/PdaNext.csproj -c Release` must
   succeed. Kotlin changes: `gradle -p core test`.
5. **Never weaken a test** (no skipping, deleting, loosening assertions). Existing tests must pass unchanged.
6. **Shared contracts** (the `PDNR` link protocol, router addresses, port 28080, the status JSON):
   change phone and PC together; only ADD status fields, never rename or remove one.
7. **Never** add a proxy, a local listener for traffic, fake-IP DNS, or change the PC's TTL.
   Never bump `VERSION`. Never commit binaries (APK, MSI, exe, scrcpy, adb).
8. **No secrets** in code, tests, logs or reports (VPN configs, UUIDs, passwords, keys).
9. **Scope:** do what your task says. Do not refactor or rename unrelated code. Do not delete code
   you do not understand (a removed `relay.attachRouter` call broke the app once).
10. Never write an AI model name in commits, PRs or code.

## The review loop with Claude
- Claude watches your draft PR. Every push and every CI result reaches Claude.
- Claude's findings arrive as **review comments on your PR**. When the owner says "read the review",
  run `gh pr view <n> --comments` (and `gh api repos/alimhbtwenty/pdanet-next/pulls/<n>/comments`
  for line comments), fix every finding in a new commit, push, wait for CI, then reply on the PR
  with one line per finding: what you changed, or why you did not.
- Done = CI green on your last commit + every review comment answered.

## Commands
| | |
| --- | --- |
| Phone engine tests | `gradle -p core test` (Maven may answer 429: wait and retry) |
| Desktop relay | `gradle -p core installDist && core/build/install/core/bin/core --bind 127.0.0.1 --port 28080` |
| Windows build | `dotnet build windows/PdaNext.csproj -c Release` |
| Windows tests | `RELAY_PORT=28080 dotnet run --project windows/tests` (relay running) |
| Linux e2e | CI runs it. Locally only in Linux/WSL as root: `bash tests/e2e/run-router-e2e.sh` |
| CI status | `gh pr checks <n> --watch`, `gh run view <id> --log-failed` |
