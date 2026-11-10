# MeloX Watch agent instructions

## Scope and source of truth

- The application is still plan-only: no Android implementation, APK, or device-test results exist. Governance/CI files do not prove that cloud settings or release automation are complete. See [actual repository status](docs/13-仓库状态.md). Do not start Android development unless the user requests it.
- Primary target: OPPO Watch X2 as a normal Android round watch without a GMS dependency. Wear OS is optional later work.
- Read [CONTRIBUTING.md](CONTRIBUTING.md), [cloud build and local validation](docs/09-云端构建与本地验证.md), and the relevant plan before changing code.
- Reuse narrowly scoped Android upstream logic. Record source path, commit, changes, and test cases; consult [upstream reuse](docs/11-上游复用实施指南.md). Never bulk-copy the phone app or execute/build research snapshots.
- `.ci/upstream-lock.json` is authoritative. Read only exact pinned commits. Never pull upstream main or silently switch references. An upgrade requires separate migration, impact analysis, and regression checks before the lock changes.

## Development and validation

1. Edit locally on `contribution` or a topic branch from it. Feature PRs target contribution; only owner-authored contribution → main promotion PRs establish the accepted baseline.
2. Run appropriate local text, workflow, and script checks only. Low-impact documentation edits need link/content review, not application tests. **Do not run Gradle, AGP, Kotlin/Java compilation, Android Studio Build/Run, or build Android APK/AAB locally.** Gradle unit tests and lint run in GitHub Actions.
3. Push only when authorized, to the authorized repository using the owner's GitHub identity and a GitHub noreply commit email. Never put real email addresses, credentials, signing material, or local upstream clones into this project, issues, PRs, artifacts, or logs. The separately authorized private recovery repository is the only signing-backup exception described below.
4. Use `Cloud build` for future Debug compilation, Gradle unit tests, and Android lint. Associate the run, source SHA, build SHA, and checksums with the change. A planning-only run does not prove an Android build passed. Cloud build does not authorize or perform a release.
5. Once an application exists, download the cloud APK, verify checksum/source/version/certificate, and test that exact artifact on the watch. Record device/firmware and results in the [validation template](docs/templates/本地验证记录.md).
6. On failure, fix locally and repeat the cloud build. Do not substitute a local APK, claim an unrun build passed, or claim device validation without a device.

## Immutable development versions and PR policy

- `main`, `contribution`, and ordinary development builds keep `version.properties` byte-for-byte at development `0.0.0 / 0`, including after releases. [Governance and release](docs/10-仓库治理与发布.md) defines the exact UTF-8, no-BOM, LF bytes, including the comment and final newline.
- `scripts/ci/check.py` must compare those fixed bytes on every normal PR, push, and manual build. Release snapshots require separate release validation. Future APK validation must inspect the actual packaged version; reading Gradle inputs alone is insufficient.
- **Every PR, including owner PRs, must reject additions, changes, deletions, and renames involving `version.properties`, `.github/workflows/`, or `.github/actions/`.** Inspect both old and new rename paths.
- `Trusted PR policy` runs through `pull_request_target`, reads trusted policy only from the fixed base SHA, and uses read-only APIs for PR files/commits. It never checks out or executes PR head/merge code or accesses signing/release secrets. Incomplete API results must fail closed.
- Request Actions changes through an Actions Issue. The owner reviews and performs direct authenticated maintenance outside PRs; there is no dedicated maintenance workflow. If protection must temporarily change, restore and verify it immediately afterward and record the Issue, exact commits, reason, and settings before/after. Never leave a permanent bypass. Other governance/build-policy changes require owner-authored PRs.
- Commits use the developer's own identity. Do not add AI collaborators, AI author/committer identities, `Co-authored-by` trailers, or generated-by AI signatures.

## Current permissions and future protection

- The project remains private. Recorded rulesets GET requests returned **403**; GitHub Pro or public visibility is needed for the required rules. No hard branch/tag protection is currently enforced. Only OnlineMo has repository write access.
- When available, protect both main and contribution with mandatory PRs, strict up-to-date `cloud-gate` and `pr-policy` checks bound to the expected GitHub App, resolved conversations, no force pushes/deletions, administrator enforcement, and no bypass actors.
- Set required approving reviews to **0** because the sole owner cannot approve their own PR. OnlineMo must still review manually and is the only person who merges.
- Tag rules must forbid updates/deletions while allowing creation only by writers. Only the owner and the owner-dispatched release token receive the needed contents-write permission; public contributors never receive write access.
- CI alone cannot prevent an administrator's direct push. The owner can always reconfigure repository protection; do not claim otherwise.

## Release contract

- Only OnlineMo may request or dispatch `Owner release` from main. Validate repository, actor, and ref, including reruns. Do not publish based on contributor requests.
- Compute versionCode as the maximum stored versionCode across all existing exact `v<major>.<minor>.<patch>` release tags plus one, starting at 1 if none exist. New SemVer must exceed all existing release versions numerically. Prerelease status is a GitHub Release flag, not a tag suffix. Incomplete/invalid tag data must stop release.
- From the reviewed main SHA, create a detached snapshot commit whose only change is `version.properties`. Build/test/lint that snapshot in the cloud and validate APK version, package, certificate, and checksum.
- Serialize releases and recheck tags before publishing. Use only the run's ephemeral `GITHUB_TOKEN` with contents-write permission for publication: push a new immutable snapshot tag and publish its Release. **Never push main/contribution or merge release snapshots back.** Do not use deploy keys or long-lived PATs.
- Preserve an existing tag if Release publication fails; recover the same Release and verified snapshot artifacts without replacing/deleting the tag or reusing its version.
- First public `0.1.0` is a prerelease after P0–P3, all [public-release gates](docs/12-首个公开版本与责任清单.md), evidence, and explicit user approval. `1.0.0` requires P0–P5. No application or device evidence exists yet.
- Publishing, comments, messages, merges, and visibility changes require existing user authorization. A build request alone does not authorize publishing or making the repository public.

## Signing and recovery

- Independent Release/Debug certificates have been generated: RSA 3072, SHA-256, 30-year validity; respective CNs are `OnlineMo MeloX Watch Release` and `OnlineMo MeloX Watch Debug`, with `O=OnlineMo` and `OU=MeloX Watch`. No C/ST/L/email attributes. Public fingerprints are pinned in [.ci/signing-certificates.json](.ci/signing-certificates.json).
- Trusted Debug builds use the stable `.dev` application ID and separate Debug certificate. Their version stays `0.0.0 / 0`; distinguish artifacts by source SHA, build SHA, and Actions run. PR builds use ephemeral certificates and no secrets. Release uses its own certificate and no `.dev` suffix.
- Use repository-scoped Actions secrets; environments may be unsupported on the current private plan. Do not claim secrets, environment approvals, or release readiness are configured/verified based on these instructions; consult the actual status document.
- The user explicitly authorized p12 files, plaintext passwords, and recovery metadata in a **separate, permanently private recovery repository**. It must have Actions disabled and no collaborators. Keep its name/address, passwords, and private signing material out of this project and public output. This narrow authorization does not permit publication of recovery contents.
- Keep local research snapshots unchanged and excluded from Git. Respect upstream licenses when implementation starts.
