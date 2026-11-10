const assert = require('node:assert/strict');
const {commitProblems, fileProblems, parseVersion, compareVersions, validateVersionAdvance} = require('./pr-policy.cjs');
const person = {name: 'OnlineMo', email: '143324986+OnlineMo@users.noreply.github.com'};
const commit = {author: person, committer: person, message: 'feat(player): support pause'};
assert.deepEqual(commitProblems(commit), []);
assert.ok(commitProblems({...commit, author: {...person, email: 'private@example.invalid'}}).length);
assert.ok(commitProblems({...commit, message: 'feat: test\n\nCo-authored-by: Claude <bot@example.invalid>'}).length);
assert.ok(commitProblems({...commit, message: 'Generated with Codex'}).length);
assert.ok(commitProblems({...commit, author: {...person, name: 'Copilot'}}).length);
assert.deepEqual(fileProblems({filename: 'version.properties'}, true), []);
assert.ok(fileProblems({filename: 'version.properties'}, false).length);
assert.ok(fileProblems({filename: 'removed.txt', previous_filename: 'version.properties'}, false).length);
assert.ok(fileProblems({filename: '.github/workflows/pr-policy.yml'}, false).length);
assert.ok(fileProblems({filename: '.github/workflows/pr-policy.yml'}, true).length);
assert.ok(fileProblems({filename: 'moved.yml', previous_filename: '.github/actions/setup-build/action.yml'}, true).length);
assert.deepEqual(fileProblems({filename: 'android/app/src/main/Main.kt'}, false), []);
console.log('PR policy scenarios passed.');
const version = (name, code) => parseVersion(`versionName=${name}\nversionCode=${code}\n`);
assert.deepEqual(version('0.0.0','0'), {versionName:'0.0.0',versionCode:'0'});
validateVersionAdvance(version('0.10.0','3'),version('0.9.0','2'));
assert.equal(compareVersions('0.10.0','0.9.0'), 1);
for (const name of ['v0.2.0', '00.2.0', '0.2', '0.2.0-beta', '0.2.0;echo']) assert.throws(()=>version(name,'2'));
for (const code of ['-1','01','1.2','2100000001','abc']) assert.throws(()=>version('0.2.0',code));
assert.throws(()=>version('0.2.0','0'));
assert.throws(()=>version('0.0.0','1'));
assert.throws(()=>parseVersion('versionName=0.2.0\nversionName=0.3.0\nversionCode=3'));
assert.throws(()=>validateVersionAdvance(version('0.2.0','2'),version('0.2.0','1')));
assert.throws(()=>validateVersionAdvance(version('0.3.0','2'),version('0.2.0','2')));
assert.throws(()=>validateVersionAdvance(version('0.1.0','3'),version('0.2.0','2')));
console.log('Version format, precedence, monotonicity and limits passed.');
const {countReachable, validateVersionCeiling, isOwnerSync} = require('./pr-policy.cjs');
const graph = [
  {sha: 'root', parents: []}, {sha: 'topic', parents: [{sha: 'root'}]},
  {sha: 'base', parents: [{sha: 'root'}]}, {sha: 'merge', parents: [{sha: 'base'}, {sha: 'topic'}]},
];
assert.equal(countReachable(graph, 'merge'), 4);
assert.throws(() => countReachable(graph.slice(1), 'merge'));
assert.throws(() => countReachable([...graph, {sha: 'orphan', parents: []}], 'merge'));
assert.throws(() => countReachable([...graph, graph[0]], 'merge'));
validateVersionCeiling(version('0.1.0', '5'), 4, []);
validateVersionCeiling(version('0.2.0', '11'), 4, [version('0.1.0', '10')]);
assert.throws(() => validateVersionCeiling(version('0.1.0', '6'), 4, []));
assert.throws(() => validateVersionCeiling(version('0.2.0', '12'), 4, [version('0.1.0', '10')]));
assert.throws(() => validateVersionCeiling(version('0.1.0', '1'), 2100000000, []));
const repository = 'OnlineMo/MeloX-Watch';
const sync = {user: {login: 'OnlineMo'}, head: {ref: 'main', repo: {full_name: repository}},
  base: {ref: 'contribution', repo: {full_name: repository}}};
assert.equal(isOwnerSync(sync, repository), true);
assert.equal(isOwnerSync({...sync, head: {...sync.head, ref: 'topic'}}, repository), false);
assert.equal(isOwnerSync({...sync, user: {login: 'other'}}, repository), false);
assert.equal(isOwnerSync({...sync, head: {...sync.head, repo: {full_name: 'other/MeloX-Watch'}}}, repository), false);
console.log('Complete-history ceiling and owner synchronization boundaries passed.');

async function testReviewedSync() {
  const {validateReviewedSync} = require('./pr-policy.cjs');
  const reviewed = {...sync, head: {...sync.head, sha: 'current-main'}};
  const promotion = {merged_at: '2026-11-11T00:00:00Z', user: {login: 'OnlineMo'},
    head: {ref: 'contribution', repo: {full_name: repository}}, merge_commit_sha: 'reviewed-merge'};
  function client(overrides = {}) {
    return {
      rest: {
        git: {getRef: async () => ({data: {object: {sha: overrides.main || 'current-main'}}})},
        repos: {
          getContent: async ({ref}) => ({data: {type: 'file', sha: ref === 'current-main' ? 'version-blob' : overrides.blob || 'version-blob'}}),
          compareCommitsWithBasehead: async () => ({data: {status: overrides.status || 'ahead'}}),
          getCommit: async () => ({data: {parents: overrides.parents || [{sha: 'base'}, {sha: 'contribution'}]}}),
        },
        pulls: {list: 'list'},
      },
      paginate: {iterator: async function* () { yield {data: overrides.promotions || [promotion]}; }},
    };
  }
  await validateReviewedSync(client(), {}, reviewed);
  for (const changes of [{main: 'new-main'}, {blob: 'unreviewed-version'}, {status: 'diverged'},
                         {parents: [{sha: 'squashed'}]}, {promotions: []},
                         {promotions: [{...promotion, user: {login: 'other'}}]}]) {
    await assert.rejects(() => validateReviewedSync(client(changes), {}, reviewed));
  }
  console.log('Exact reviewed sync bytes, current main and merge ancestry passed.');
}
testReviewedSync().catch(error => { console.error(error.message); process.exitCode = 1; });
