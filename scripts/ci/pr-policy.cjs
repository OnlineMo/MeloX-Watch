// Loaded only from the PR base commit by pull_request_target.
const OWNER = 'onlinemo';
const workflowPath = /^\.github\/(?:workflows|actions)\//;
const protectedPath = /^(?:\.github\/|\.ci\/|scripts\/ci\/|AGENTS\.md$|CONTRIBUTING\.md$|version\.properties$)/;
const aiIdentity = /(?:\b(?:codex|claude|copilot|chatgpt|openai|anthropic|gemini|cursor|aider)\b)/i;

function parseVersion(text) {
  const values = {};
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim() || line.trimStart().startsWith('#')) continue;
    const match = /^\s*(versionName|versionCode)\s*=\s*([^\s]+)\s*$/.exec(line);
    if (!match || Object.hasOwn(values, match[1])) throw new Error('Version file has unknown, duplicate or malformed properties');
    values[match[1]] = match[2];
  }
  if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(values.versionName || '')) {
    throw new Error('versionName must be x.y.z without v, suffixes or leading zeros');
  }
  if (!/^(0|[1-9]\d*)$/.test(values.versionCode || '') || BigInt(values.versionCode) > 2100000000n) {
    throw new Error('versionCode must be an integer from 0 to 2100000000');
  }
  if ((values.versionName === '0.0.0') !== (values.versionCode === '0')) {
    throw new Error('Zero is reserved for the initial 0.0.0 / 0 development state');
  }
  return values;
}

function compareVersions(left, right) {
  const a = left.split('.').map(BigInt), b = right.split('.').map(BigInt);
  for (let i = 0; i < 3; i++) {
    if (a[i] !== b[i]) return a[i] > b[i] ? 1 : -1;
  }
  return 0;
}

function validateVersionAdvance(candidate, previous) {
  if (compareVersions(candidate.versionName, previous.versionName) <= 0 || BigInt(candidate.versionCode) <= BigInt(previous.versionCode)) {
    throw new Error('Both versionName and versionCode must strictly exceed the target branch and every existing release');
  }
}

async function readVersion(github, repo, ref) {
  const {data} = await github.rest.repos.getContent({...repo, path:'version.properties', ref});
  if (data.type !== 'file' || data.encoding !== 'base64') throw new Error('version.properties must be a regular file');
  return parseVersion(Buffer.from(data.content, 'base64').toString('utf8'));
}

function countReachable(commits, head) {
  const objects = new Map(commits.map(commit => [commit.sha, commit]));
  if (objects.size !== commits.length || objects.size > 100000) throw new Error('Invalid or excessive history inventory');
  const pending = [head], seen = new Set();
  while (pending.length) {
    const sha = pending.pop();
    if (seen.has(sha)) continue;
    const commit = objects.get(sha);
    if (!commit) throw new Error('Incomplete Git history; candidate code cannot be estimated');
    seen.add(sha);
    pending.push(...commit.parents.map(parent => parent.sha));
  }
  if (seen.size !== objects.size) throw new Error('History includes unreachable objects');
  return seen.size;
}

async function fullHistoryCount(github, repo, head) {
  // 只读 API 提供的 Git commit/parent 对象，不 checkout 或执行 PR head。
  const commits = [];
  for await (const response of github.paginate.iterator(github.rest.repos.listCommits, {...repo, sha: head, per_page: 100})) {
    commits.push(...response.data);
    if (commits.length > 100000) throw new Error('History exceeds bounded policy traversal; owner review required');
  }
  return countReachable(commits, head);
}

function validateVersionCeiling(candidate, count, history) {
  if (!Number.isSafeInteger(count) || count < 1) throw new Error('Full reachable commit count is required');
  const highest = history.reduce((value, version) => BigInt(version.versionCode) > value ? BigInt(version.versionCode) : value, 0n);
  const ceiling = BigInt(count) + 1n > highest + 1n ? BigInt(count) + 1n : highest + 1n;
  if (ceiling > 2100000000n || BigInt(candidate.versionCode) > ceiling) throw new Error('Owner versionCode exceeds the automatic candidate ceiling');
}

function isOwnerSync(pr, repository) {
  return pr.user.login.toLowerCase() === OWNER && pr.head.ref === 'main' && pr.base.ref === 'contribution'
    && pr.head.repo?.full_name === repository && pr.base.repo?.full_name === repository;
}

async function validateReviewedSync(github, repo, pr) {
  const {data: main} = await github.rest.git.getRef({...repo, ref: 'heads/main'});
  if (main.object.sha !== pr.head.sha) throw new Error('Sync must use current main');
  const {data: current} = await github.rest.repos.getContent({...repo, path: 'version.properties', ref: pr.head.sha});
  let inspected = 0;
  for await (const response of github.paginate.iterator(github.rest.pulls.list, {...repo, base: 'main', state: 'closed', sort: 'updated', direction: 'desc', per_page: 100})) {
    for (const promotion of response.data) {
      if (++inspected > 1000) throw new Error('Promotion history exceeds bounded policy review');
      if (!promotion.merged_at || promotion.user.login.toLowerCase() !== OWNER || promotion.head.ref !== 'contribution'
          || promotion.head.repo?.full_name !== pr.head.repo.full_name) continue;
      const {data: comparison} = await github.rest.repos.compareCommitsWithBasehead({...repo, basehead: `${promotion.merge_commit_sha}...${pr.head.sha}`});
      if (!['ahead', 'identical'].includes(comparison.status)) continue;
      const {data: reviewed} = await github.rest.repos.getContent({...repo, path: 'version.properties', ref: promotion.merge_commit_sha});
      const {data: merged} = await github.rest.repos.getCommit({...repo, ref: promotion.merge_commit_sha});
      if (merged.parents.length !== 2) throw new Error('Reviewed promotions must preserve ancestry with a merge commit');
      if (current.type === 'file' && current.sha === reviewed.sha) return;
      // 最近的祖先提升已足以确定已审阅字节；不从更旧记录寻找任意可放行值。
      throw new Error('Sync version bytes differ from the reviewed main promotion');
    }
  }
  throw new Error('No reviewed owner promotion proves these sync version bytes');
}

function commitProblems(commit) {
  const errors = [];
  for (const role of ['author', 'committer']) {
    const person = commit[role] || {};
    const email = person.email || '';
    if (!/^[^\s@]+@users\.noreply\.github\.com$/i.test(email) && email !== 'noreply@github.com') {
      errors.push(`${role}: use a GitHub noreply email`);
    }
    if (aiIdentity.test(person.name || '') || aiIdentity.test(email)) errors.push(`${role}: AI attribution is prohibited`);
  }
  const message = commit.message || '';
  // Project convention: use no co-author trailers, avoiding accidental AI trailers.
  if (/^\s*co-authored-by\s*:/im.test(message)) errors.push('Co-authored-by trailers are prohibited');
  if (/(?:generated|authored|assisted|co.?authored)\s+(?:with|by)\s+.*(?:codex|claude|copilot|chatgpt|openai|anthropic|gemini|cursor|aider)/i.test(message)) {
    errors.push('AI generation/author signatures are prohibited');
  }
  return errors;
}

function fileProblems(file, isOwner) {
  const paths = [file.filename, file.previous_filename].filter(Boolean);
  if (!isOwner && paths.includes('version.properties')) return ['Only OnlineMo may change version.properties; contributor PRs must not change version metadata'];
  if (paths.some(path => workflowPath.test(path))) return ['WORKFLOW CHANGE BLOCKED: open an Actions change Issue; workflows/actions must not be changed through PRs, including owner PRs'];
  if (!isOwner && paths.some(path => protectedPath.test(path))) return ['Build/governance policy changes require an owner-authored PR'];
  return [];
}

async function run({github, context, core}) {
  const pr = context.payload.pull_request;
  if (!pr) throw new Error('This check requires a pull_request_target event');
  const isOwner = pr.user.login.toLowerCase() === OWNER;
  if (pr.base.ref === 'main') {
    if (!isOwner || pr.head.ref !== 'contribution' || pr.head.repo?.full_name !== context.payload.repository.full_name) {
      throw new Error('main accepts only owner-authored contribution -> main promotion PRs');
    }
  } else if (pr.base.ref !== 'contribution') {
    throw new Error('Feature PRs must target contribution');
  }
  // The API caps these endpoints. Fail closed instead of overlooking omitted files/commits.
  if (pr.changed_files > 3000 || pr.commits > 250) throw new Error('PR too large for complete policy review; split it');
  const params = {...context.repo, pull_number: pr.number, per_page: 100};
  const files = await github.paginate(github.rest.pulls.listFiles, params);
  if (files.length !== pr.changed_files) throw new Error('Incomplete changed-file inventory');
  const workflowChanges = [...new Set(files.flatMap(file => [file.filename, file.previous_filename].filter(Boolean)).filter(path => workflowPath.test(path)))];
  if (workflowChanges.length) {
    core.error('WORKFLOW MODIFICATION DETECTED. This PR is blocked. Submit an Actions change Issue instead.');
    await core.summary.addHeading('WARNING: GitHub Actions modifications blocked', 2)
      .addRaw('These files change the build/release trust boundary. Open an Actions change Issue; do not merge this PR.\n')
      .addList(workflowChanges).write();
  }
  for (const file of files) {
    for (const problem of fileProblems(file, isOwner)) core.setFailed(`${file.filename}: ${problem}`);
  }
  const versionChanged = files.some(file => [file.filename, file.previous_filename].includes('version.properties'));
  const pureSync = isOwnerSync(pr, context.payload.repository.full_name);
  if (isOwner && versionChanged && pureSync) {
    await validateReviewedSync(github, context.repo, pr);
    await core.summary.addHeading('Reviewed main version synchronized', 2)
      .addRaw('Exact reviewed main bytes; this sync does not allocate a new version. Use a merge commit.\n').write();
  } else if (isOwner && versionChanged) {
    const candidate = await readVersion(github, {owner:pr.head.repo.owner.login, repo:pr.head.repo.name}, pr.head.sha);
    const baseline = await readVersion(github, context.repo, pr.base.sha);
    validateVersionAdvance(candidate, baseline);
    const tags = await github.paginate(github.rest.repos.listTags, {...context.repo, per_page:100});
    if (tags.length > 1000) throw new Error('Release history too large for this policy; owner must review the policy before continuing');
    const history = [];
    for (const tag of tags) {
      if (!tag.name.startsWith('v')) continue;
      if (!/^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(tag.name)) throw new Error('Noncanonical release tag');
      const released = await readVersion(github, context.repo, tag.commit.sha);
      if ('v' + released.versionName !== tag.name || released.versionCode === '0') throw new Error('Existing release tag metadata is invalid');
      validateVersionAdvance(candidate, released);
      history.push(released);
    }
    const count = await fullHistoryCount(github, context.repo, pr.head.sha);
    validateVersionCeiling(candidate, count, history);
    await core.summary.addHeading('Owner version change validated', 2)
      .addRaw(`Version ${candidate.versionName} / ${candidate.versionCode} exceeds the base branch and published tag history.\n`).write();
  }
  const commits = await github.paginate(github.rest.pulls.listCommits, params);
  if (commits.length !== pr.commits) throw new Error('Incomplete commit inventory');
  for (const entry of commits) {
    for (const problem of commitProblems(entry.commit)) core.setFailed(`${entry.sha.slice(0, 12)}: ${problem}`);
  }
  await core.summary.addHeading('Trusted PR policy').addRaw('Checked base-branch policy, version ownership, governance paths, commit privacy and attribution. No PR code was executed.').write();
}

module.exports = {run, commitProblems, fileProblems, parseVersion, compareVersions, validateVersionAdvance,
  countReachable, validateVersionCeiling, isOwnerSync, validateReviewedSync};
