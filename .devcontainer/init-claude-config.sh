#!/usr/bin/env bash
#
# Answers Claude Code's first-run questions before it is started, then chains to
# the base image's entrypoint unchanged.
#
# Without this the container asks you to log in even though
# CLAUDE_CODE_OAUTH_TOKEN is set — the first-run wizard runs whenever
# .claude.json has no onboarding recorded, and it never consults the variable.
#
# Baking the file into the image is not enough on its own. $CLAUDE_CONFIG_DIR is
# a mount point, and Docker seeds a named volume from the image only when the
# volume is *empty*. A volume left over from an earlier build therefore never
# receives the seed, Claude Code writes a fresh .claude.json without the
# onboarding keys, and the wizard is back. Merging at every start is idempotent
# and does not care how old the volume is.
#
# Runs as the ENTRYPOINT, so it covers however the container is started;
# devcontainer.json calls it again from postStartCommand, because the
# devcontainer spec overrides the entrypoint.

set -euo pipefail

DEFAULTS=/usr/local/share/claude-onboarding.json
config_dir="${CLAUDE_CONFIG_DIR:-${HOME}/.claude}"
config="${config_dir}/.claude.json"

# Never fatal: a container that cannot write its config should still start, and
# the worst case is the wizard this exists to avoid.
if [[ -w "${config_dir}" ]]; then
    if [[ -f "${config}" ]]; then
        # Existing keys win, so a login, a theme change or a trusted path
        # recorded by an earlier session is never undone. jq's * merges
        # recursively, which matters for the per-project map.
        if merged="$(jq -s '.[0] * .[1]' "${DEFAULTS}" "${config}" 2>/dev/null)"; then
            # Redirect rather than mv, to keep the file's 0600 mode.
            printf '%s\n' "${merged}" > "${config}"
        fi
    else
        install -m 0600 "${DEFAULTS}" "${config}"
    fi
fi

# With arguments this is the entrypoint and hands off; without, it is the
# postStartCommand and its work is done.
if [[ "$#" -gt 0 ]]; then
    exec /usr/local/bin/entrypoint "$@"
fi
