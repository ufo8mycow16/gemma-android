---
name: release-gemma
description: Releasing a new version of Gemma (server and Android app): tests, build, tag, install.
triggers: release gemma, new version of gemma, ship gemma, update gemma, publish the apk, release build
tools: local_git, github, workspace
---
Releasing Gemma:
1. Check the working tree is clean and on the right branch (git_status); show what changed since the last release (git_log, git_diff).
2. Run the server tests: python3 tests/test_documents.py, tests/test_turns_v2.py, tests/test_platform.py (all must print OK).
3. Stamp the build: python3 tools/stamp_build.py (writes BUILD_INFO.json: version, build, commit, time).
4. Back up Gemma's data before installing: python3 app.py --backup.
5. Android: pushing the branch triggers the GitHub build; check the run (github_ci_runs) and the release asset (github_releases).
6. Commit, tag and push only with Glen's approval (each shows an approval card). Never force-push.
7. After installing: open /diagnostics and check the build shown matches the commit.
