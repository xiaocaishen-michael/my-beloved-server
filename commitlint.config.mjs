// Conventional Commits enforcement.
// Used by both CI (wagoid/commitlint-github-action) and PR title check
// (amannn/action-semantic-pull-request reads this same config).
// Local fast-path runs as a regex check via lefthook commit-msg hook
// (see lefthook.yml) to avoid a hard Node dependency on every commit.
export default {
  extends: ['@commitlint/config-conventional'],
  rules: {
    // Allow any scope (business modules: account / pkm / billing / ...);
    // we don't enforce scope-enum because the module list is in
    // meta-repo's docs/conventions/business-naming.md and would drift.
    'scope-enum': [0],
    // Header up to 100 chars (default 72 too tight for Chinese summaries).
    'header-max-length': [2, 'always', 100],
    // Body 150 chars (default 100 too tight; mirrors meta + app commitlint).
    'body-max-line-length': [2, 'always', 150],
    'subject-case': [0], // mixed Chinese/English; case rules don't apply
  },
  // Skip body line-length check for dependabot — its auto-generated body
  // includes long URLs from upstream release notes that we cannot reflow.
  ignores: [(message) => /Signed-off-by: dependabot\[bot\]/.test(message)],
};
