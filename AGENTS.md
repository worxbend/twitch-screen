# Agent instructions

## Git workflow: commit and push to `main`

- `main` is the only integration branch. Base all work, reviews and
  remediation on the current `origin/main`.
- Always commit finished, validated work and push it to `main`
  (`git push origin HEAD:main`). Do not leave work on feature branches or
  open pull requests unless the owner asks for one.
- Before pushing, rebase onto the latest remote (`git pull --rebase origin main`)
  and rerun the checks affected by the change. Never force-push `main`.
- Parallel agents may use temporary worktrees or local branches, but each
  validated change must be rebased onto `origin/main` and pushed to `main`.
  Delete temporary branches when finished.
- Use small conventional commits (`fix:`, `test:`, `docs:`, `ci:`, `build:`,
  `refactor:`) with a body naming the review finding IDs addressed, if any.
