# Repository workflow

## Branches and pull requests

- Start each implementation from the latest `origin/main`. Confirm a clean worktree, fetch, switch to `main`, and fast-forward it before creating a `codex/<short-task-slug>` branch.
- Keep implementation commits on that branch. Build and verify the change, push the branch, then open a pull request into `main` with a short summary, evidence, and unverified checks.
- Leave pull requests open for the repository owner to merge. Do not merge pull requests or push application changes directly to `main`.
- After the owner reports a merge, verify the pull request state, fetch `origin`, fast-forward local `main`, and create the next work branch from it.
- If the repository has no `main` branch, create a minimal base branch first and put application changes in a pull request.
