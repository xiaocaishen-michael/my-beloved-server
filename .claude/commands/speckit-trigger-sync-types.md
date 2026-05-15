---
description: After /speckit.implement on server, trigger app /sync-api-types if HTTP layer changed
allowed-tools: Bash
---

You have been triggered by the spec-kit `after_implement` hook in the **server**
repo (`my-beloved-server`). The hook context provides `FEATURE_DIR` (the server
feature dir whose implementation just finished).

This command **does not auto-commit / auto-push / auto-PR**. It regenerates
types in the app working tree and reports next-step commands; the user opens
an independent app PR per sdd.md § server impl 后的 app types 同步.

## Steps

1. **Locate tasks.md & detect HTTP-layer change**

   ```bash
   SERVER_ROOT=$(git rev-parse --show-toplevel)
   TASKS_MD="${FEATURE_DIR}/tasks.md"
   [[ -f "$TASKS_MD" ]] || { echo "no tasks.md at $TASKS_MD; skip"; exit 0; }

   # Match both legacy ✅ emoji and new [X] checkbox conventions
   if ! grep -E '(✅|\[X\]).*\[(Web|Contract)\]' "$TASKS_MD" >/dev/null; then
       echo "No completed [Web]/[Contract] task in $TASKS_MD; skip API types sync"
       exit 0
   fi
   ```

2. **Probe dev server liveness** — assumption: user keeps dev server running
   per project convention. On failure: surface remediation, exit 0 (hook is
   `optional: true`; must not block server impl).

   ```bash
   if ! curl -sf -m 3 http://localhost:8080/v3/api-docs >/dev/null; then
       cat >&2 <<EOF
   ❌ dev server not running on :8080
   API types sync skipped. To recover:
     1. Start dev server:  cd $SERVER_ROOT && ./scripts/dev-server.sh
     2. In app cwd:        pnpm api:gen:dev && pnpm typecheck
   EOF
       exit 0
   fi
   ```

3. **Locate app repo (sibling layout)**

   ```bash
   META_ROOT=$(cd "$SERVER_ROOT/.." && pwd)
   APP_ROOT="$META_ROOT/no-vain-years-app"
   [[ -d "$APP_ROOT" ]] || {
       echo "❌ app repo not found at $APP_ROOT; skip API types sync" >&2
       exit 0
   }
   ```

4. **Cross-cwd regenerate types + typecheck**

   ```bash
   (cd "$APP_ROOT" && pnpm api:gen:dev && pnpm typecheck)
   ```

   If pnpm is not on PATH in the hook shell, fall back to the absolute path
   (run `which pnpm` in user's interactive shell to find it) — Q4 of the
   FW-2 plan flags this as a Phase-2.3-time observation.

5. **Report changes + next steps**

   ```bash
   echo ""
   echo "=== Generated files diff (app) ==="
   git -C "$APP_ROOT" diff --stat packages/api-client/src/generated/
   echo ""
   echo "=== Next step (manual) ==="
   echo "  cd $APP_ROOT"
   echo "  git add packages/api-client/src/generated/"
   echo "  git commit -m 'chore(api-client): sync types — <feature-slug>'"
   echo "  Open INDEPENDENT app PR (do not mix into server impl PR)"
   ```

## Failure handling

All non-fatal paths exit 0 — hook is `optional: true` and must not roll back
the server impl. Surfaced cases:

- `tasks.md` missing → silent skip
- No completed `[Web]` / `[Contract]` task → silent skip (pure domain impl)
- Dev server not running on :8080 → remediation message + exit 0
- App repo not at sibling path (副 worktree without app sibling) → log + exit 0

`pnpm` itself failing (non-zero exit during step 4) bubbles up but is treated
as soft-fail by spec-kit per `optional: true`.

## Notes

- Slash command name `/speckit-trigger-sync-types` ↔ hook command name
  `speckit.trigger-sync-types` (per spec-kit dot → hyphen convention; see
  speckit-specify SKILL.md "Pre-Execution Checks" section).
- Ships as part of `api-types-sync` preset from michael-speckit-presets.
- Together with `extensions.yml.fragment` registering the `after_implement` hook.
- Cross-repo PR boundary: types regen is **always** an independent app PR.
  Rationale: PR description can't span repos; app CI must validate types
  independently; generated diff sometimes needs user review (null/required
  field semantic changes).
- This hook **complements** (not replaces) sdd.md § server impl 后的 app types
  同步. If the hook silent-skips, dev server is down, or user works outside
  spec-kit, the sdd.md fallback (manual `/sync-api-types` in app cwd) still
  applies.
