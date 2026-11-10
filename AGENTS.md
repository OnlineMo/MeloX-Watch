# MeloX Watch agent instructions

## Start here and scope

- The user authorized implementation, then narrowed this session to development prerequisites: CI-01–04, toolchain/wrapper and read-only device checks. Do not create Android probe or business code until the user resumes that scope. The user is adding planning details; read their changed contracts before application work. No Android source, APK or device acceptance exists.
- Read [20 final review and startup order](docs/20-终审结论与开工清单.md), [05 tasks](docs/05-开发阶段与任务清单.md), [13 actual status](docs/13-仓库状态.md), and the documents relevant to the current task. Read the entire relevant contract before editing it; do not reread all documents for every small change.
- Product: a local/online music app for **Android smartwatches**, not an OPPO-specific client or a CI/planning product. Watch X2 is only the user's first available physical test device. Adapt by actual API/ABI/window shape/size/input capabilities, no model whitelist or OPPO assumptions; distinguish round and rectangular layouts. No mandatory GMS or companion. Unmeasured devices are not declared supported. Performance first, visual quality second, readable code mandatory; correctness, privacy and accessible controls are not tradeoffs.
- First prerelease includes online NetEase, complete account-independent local music, and optional user-configured/self-hosted Meting (default off, no bundled public endpoint). Online downloads are P4. Unknown future providers get only necessary generic interfaces, not speculative SDKs/modules or fixed capability assumptions.
- Scope/FR lives in02, architecture in03, tasks/estimates in05, tests in06, performance/visual/readability in14/15/16, local in17, generic interfaces in18, Meting in19. Conflicts must be resolved in those sources, not by choosing an easier historical instruction.

## Current infrastructure limitations

- CI-01–04 scripts are under implementation; workflow changes remain proposed until owner Issue review and maintenance. Consult docs/13 and docs/ci/CI-01–04建设记录.md for actual evidence. The legacy deployed workflows are unsafe for trusted application signing/release; never use them by setting gates true.
- Before trusted Android builds, implement CI-01: compile/test/lint with no signing secrets or write token; sign on a fresh runner without running application build scripts; grant ephemeral contents-write only to publication. PR event names alone do not ensure secret isolation.
- CI-02/03 must provide nonpublishing candidates and publication/recovery of the exact accepted APK. CI-04 must align owner version proposals, auto versionCode and branch resynchronization. Workflow changes follow the owner's Issue maintenance route. Policy tests and skipped Android jobs do not complete APK/device acceptance.

## Development loop

1. Work from contribution on a topic branch. Read the relevant task and preserve existing work. P0 starts with read-only device information and DEV-004-A cloud-built minimum probe; DEV-002/003/005 use it. DEV-004-B measures the baseline. P1 evolves that project; it does not wait for every unrelated business probe.
2. Edit locally. **No local Gradle sync/build/test/lint, Kotlin/Java compilation, Android Studio Build/Run, APK/AAB or benchmark compilation.** Local text/policy checks, artifact checksums, adb installation, device tests and trace analysis are allowed within existing authorization.
3. Run applicable GitHub Actions. A planning-only run or skipped Android job is not an application build. Track source/build SHA, run/attempt/artifact and hash. Download and test exactly the cloud artifact; no local replacement APK.
4. Measure affected metrics against14 and actual device conditions; use15's representative matrix and the compact templates. Missing device/tool evidence is untested, not zero or pass. Functionality, cloud validation, device acceptance and release approval are distinct states.
5. One Media3 service/player serves all sources. Bound tasks/cache/scan/requests, stop hidden UI work, propagate cancellation. Preserve local files and library on account logout or failed scan. Comments explain reasons/contracts; avoid narration, dead code or needless abstractions. TODOs may reference real DEV/CI tasks.
6. Keep upstream references at exact SHA in `.ci/upstream-lock.json`, clean detached local snapshots. Never execute/build research clones or silently pull latest. Reuse narrowly with source/license and regression records; source upgrades are separate migrations.

## Identity, PRs and protection

- Use the authorized developer identity and GitHub noreply email. No real user email, AI author/committer, Co-authored-by or generated-by-AI signatures. Never copy credentials, private signing material, local prompt or reference clones into the app repository, issues, logs or artifacts. Preserve necessary upstream license notices after source review.
- Ordinary contributors cannot change version.properties. OnlineMo may propose a valid increasing version; the target auto-code/sync rules are in10 and require CI-04 before relying on them. Current 0.0.0/0 is an unstarted sentinel, not a permanent byte lock.
- **All PRs, including owner PRs, must not change `.github/workflows/` or `.github/actions/`, including add/delete/rename.** Request changes via Actions Issue; owner reviews and maintains outside PRs. Other governance/CI script changes require owner PRs. Trusted policy reads base-controlled policy, never executes PR source.
- Main takes owner contribution→main promotion PRs. After protected merges, owner main→contribution sync PRs keep ancestry current; pure sync must not invent a new version. CI-04 adds this tested handling. Do not use direct pushes to bypass effective protection.
- The repo is private and prior API calls returned403 for branch/tag protection. Consult13; do not claim enforcement or upgrade/pay/change visibility automatically. Only OnlineMo has write access.
- **GOV-PUBLIC-01 is already requested:** after an independently owner-approved visibility change, immediately apply and read back10 section6 main/contribution/tag protections before release. Do not ask again for the same setup or wait for a reminder. Require PR, strict cloud-gate/pr-policy bound to GitHub Actions, resolved conversations, no force/delete/admin bypass. Approval count0 avoids sole-owner self-review deadlock. Failure blocks release; recovery repository remains private.

## Candidate and release contract

- Only OnlineMo may prepare/publish from main, including reruns. Target web workflow has prepare-candidate and publish-candidate (not available yet). Candidate preparation is not publication approval and does not require final device report or public visibility.
- Candidate source SHA is frozen main. Full reachable count N plus the release commit and historical max R produce code=max(N+1,R+1), within2100000000. Canonical x.y.z must increase over releases. Snapshot changes only version.properties; preserve its Git objects and the signed APK. No release branch or development-branch writeback.
- Device reports bind exact candidate/run/attempt/artifact/source/snapshot/APK/certificate. Later approval-only commits may change only agreed docs/releases/<version>/ reports, docs/13 status and readiness (never application build inputs). They do not rebase/rebuild the candidate or recalculate code. Other input changes invalidate it.
- Publication checks real gates, owner approval, latest release history and effective protections, then uploads the **same APK and snapshot**, without Gradle or re-signing. Only ephemeral GITHUB_TOKEN writes the tag/Release. No PAT/deploy key.
- Recovery may fill missing assets only when existing tag/snapshot and asset hashes match; never overwrite a tag or different bytes. Missing/expired original candidate requires recovery of original bytes or a newer version with fresh acceptance, not rebuilding under the existing tag.
- Readiness booleans are summaries, not evidence. Do not set them early or reuse a prior version's approval. Public visibility and release require separate concrete approval. Details and owner responsibilities are in10/12; no speculative publication or announcements.

## Signing and recovery

- Stable Debug uses `.dev` and its own certificate; PR builds use temporary Debug signing without secrets. Official candidate/release uses the independent certificate pinned in `.ci/signing-certificates.json`. Signature mismatch is not permission to uninstall or erase data.
- The user separately authorized p12, plaintext recovery passwords and metadata in a permanently private recovery repository, owner-only with Actions disabled. Do not publish it or mention its identifier in tracked/public material. Keep recovery out of application build inputs, history and artifacts.
- Secret names/pins existing do not prove isolated signing or actual upgrade tests passed. Record observed state and remaining CI/device prerequisites in13 rather than treating policy text as completed work.
