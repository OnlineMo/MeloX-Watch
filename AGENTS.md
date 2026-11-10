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

## Versions and PR policy

- Current `0.0.0 / 0` only marks an unstarted application; there is no permanent version value or byte-for-byte guard. Normal builds read `version.properties`, and APK validation checks the packaged version against it.
- Version protection checks PR author and changed paths, with no constant-byte guard. When OnlineMo changes the version in a PR, automatically validate exact `x.y.z` syntax (no `v`, suffix, or leading zeros), an integer versionCode no greater than 2100000000, and strict increases of both versionName and versionCode over the base branch and every release tag. Compare version components numerically; `0.0.0 / 0` is only the initial sentinel.
- **Reject additions, changes, deletions, or renames involving `version.properties` only when the PR author is not `OnlineMo`. Owner-authored PRs may update versions on contribution/main.** For `.github/workflows/` and `.github/actions/`, all PRs remain prohibited, including owner PRs. Inspect both old and new rename paths.
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

- Only OnlineMo may request or dispatch `Owner release` from main. Use GitHub's web UI: Actions → Owner release → Run workflow; no local computer or command line is needed to trigger the cloud release. Validate repository, actor, and ref, including reruns. Discussing this option does not authorize or execute a release.
- Release requests must use exact `x.y.z`, exceed every existing exact `v<major>.<minor>.<patch>` release tag, and be at least main's prepared version. If equal to main's candidate, use main's prepared versionCode only if it exceeds every released code. If newer, use `max(maxReleasedCode + 1, mainVersionCode + 1)`; take maxReleasedCode as 0 when no release tags exist. Enforce the integer limit of 2100000000 and fail on incomplete/invalid version data. Prerelease status is a GitHub Release flag, not a version suffix.
- In the temporary cloud runner, create a detached snapshot commit from the reviewed main SHA, changing only `version.properties`, and tag that commit. No release branch is created; repository branches remain main, contribution, and ordinary topic branches. Build/test/lint the snapshot and validate APK version, package, certificate, and checksum.
- Serialize releases and recheck tags before publishing. Use only the run's ephemeral `GITHUB_TOKEN` with contents-write permission for publication: push a new immutable snapshot tag and publish its Release. **Never push main/contribution or merge release snapshots back.** Do not use deploy keys or long-lived PATs.
- Preserve an existing tag if Release publication fails; recover the same Release and verified snapshot artifacts without replacing/deleting the tag or reusing its version.
- First public `0.1.0` is a prerelease after P0–P3, all [public-release gates](docs/12-首个公开版本与责任清单.md), evidence, and explicit user approval. `1.0.0` requires P0–P5. No application or device evidence exists yet.
- Publishing, comments, messages, merges, and visibility changes require existing user authorization. A build request alone does not authorize publishing or making the repository public.

## Signing and recovery

- Independent Release/Debug certificates have been generated: RSA 3072, SHA-256, 30-year validity; respective CNs are `OnlineMo MeloX Watch Release` and `OnlineMo MeloX Watch Debug`, with `O=OnlineMo` and `OU=MeloX Watch`. No C/ST/L/email attributes. Public fingerprints are pinned in [.ci/signing-certificates.json](.ci/signing-certificates.json).
- Trusted Debug builds use the stable `.dev` application ID and separate Debug certificate. Their version comes from `version.properties`; distinguish artifacts by source SHA, build SHA, and Actions run. PR builds use ephemeral certificates and no secrets. Release uses its own certificate and no `.dev` suffix.
- Use repository-scoped Actions secrets; environments may be unsupported on the current private plan. Do not claim secrets, environment approvals, or release readiness are configured/verified based on these instructions; consult the actual status document.
- The user explicitly authorized p12 files, plaintext passwords, and recovery metadata in a **separate, permanently private recovery repository**. It must have Actions disabled and no collaborators. Keep its name/address, passwords, and private signing material out of this project and public output. This narrow authorization does not permit publication of recovery contents.
- Keep local research snapshots unchanged and excluded from Git. Respect upstream licenses when implementation starts.
