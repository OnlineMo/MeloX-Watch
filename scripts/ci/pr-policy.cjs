// Loaded only from the PR base commit by pull_request_target.
const OWNER = 'onlinemo';
const workflowPath = /^\.github\/(?:workflows|actions)\//;
const protectedPath = /^(?:\.github\/|\.ci\/|scripts\/ci\/|AGENTS\.md$|CONTRIBUTING\.md$|version\.properties$)/;
const aiIdentity = /(?:\b(?:codex|claude|copilot|chatgpt|openai|anthropic|gemini|cursor|aider)\b)/i;
const developmentVersion = '# Development branches keep this exact file; versions exist only in release tags.\nversionName=0.0.0\nversionCode=0\n';

function versionProblems(file) {
  if (file.type !== 'file' || file.encoding !== 'base64' || Buffer.from(file.content || '', 'base64').toString('utf8') !== developmentVersion) {
    return ['Development version.properties must stay byte-for-byte at the fixed 0.0.0 / 0 baseline'];
  }
  return [];
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
  if (paths.includes('version.properties')) return ['PRs must not change version.properties; use Owner release'];
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
  const commits = await github.paginate(github.rest.pulls.listCommits, params);
  if (commits.length !== pr.commits) throw new Error('Incomplete commit inventory');
  for (const entry of commits) {
    for (const problem of commitProblems(entry.commit)) core.setFailed(`${entry.sha.slice(0, 12)}: ${problem}`);
  }
  // Read data through the API; do not execute any code from the proposed tree.
  for (const [label, ref] of [['base', pr.base.sha], ['head', pr.head.sha]]) {
    const {data} = await github.rest.repos.getContent({...context.repo, path: 'version.properties', ref});
    for (const problem of versionProblems(data)) core.setFailed(`${label}: ${problem}`);
  }
  await core.summary.addHeading('Trusted PR policy').addRaw('Checked base-branch policy, version ownership, governance paths, commit privacy and attribution. No PR code was executed.').write();
}

module.exports = {run, commitProblems, fileProblems, versionProblems, developmentVersion};
