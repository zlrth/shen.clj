#!/usr/bin/env bash
#
# Default-deny egress for the dev container.
#
# Container isolation bounds what an agent can *write*; it does nothing about
# what it can *send*. This script closes that gap: OUTPUT defaults to DROP and
# only the hosts this repo and Claude Code actually need are allowed through.
#
# Run as root at container start (devcontainer.json wires it to
# postStartCommand). iptables rules live in the network namespace, which is
# rebuilt on restart, so this has to run on every start rather than once at
# build time.
#
# Limitation worth knowing: allowlisting is by resolved IP, captured now. The
# CDNs behind Maven Central and GitHub rotate addresses, so a container left up
# for days may start seeing dropped connections. Re-run the script to refresh.

set -euo pipefail
IFS=$'\n\t'

if [[ "${EUID}" -ne 0 ]]; then
    echo "init-firewall: must run as root (try: sudo $0)" >&2
    exit 1
fi

# Hosts Claude Code itself talks to. Deliberately short: telemetry rides on
# api.anthropic.com, and the binary carries no separate statsig or sentry
# hostname, so there is nothing else to open up. claude.ai covers the installer
# and artifact publishing.
CLAUDE_DOMAINS=(
    api.anthropic.com
    claude.ai
)

# Hosts this repo's build needs: Maven Central and Clojars for :deps, plus the
# GitHub hostnames that io.github.cognitect-labs/test-runner is fetched from.
BUILD_DOMAINS=(
    repo1.maven.org
    repo.maven.apache.org
    repo.clojars.org
    codeload.github.com
    objects.githubusercontent.com
    raw.githubusercontent.com
)

# Anything extra the caller wants, space- or comma-separated.
read -r -a EXTRA_DOMAINS <<< "${EXTRA_ALLOWED_DOMAINS:-}"

echo "init-firewall: resolving allowlist before locking egress down"

# Start from a clean slate so re-running is idempotent.
iptables -F
iptables -X
iptables -t nat -F
iptables -t nat -X
iptables -t mangle -F
iptables -t mangle -X
ipset destroy allowed-egress 2>/dev/null || true
ipset create allowed-egress hash:net

# GitHub publishes its ranges, which is more durable than resolving the
# hostnames: git operations land on addresses a single A record never sees.
# Non-fatal, because the explicit hostnames below cover the common paths.
if github_meta="$(curl -fsS -m 20 https://api.github.com/meta)"; then
    while read -r cidr; do
        [[ -z "${cidr}" || "${cidr}" == *:* ]] && continue
        ipset add allowed-egress "${cidr}" 2>/dev/null || true
    done < <(echo "${github_meta}" | jq -r '(.git + .web + .api)[]?')
    echo "init-firewall: added GitHub published ranges"
else
    echo "init-firewall: WARNING could not fetch api.github.com/meta; relying on resolved hostnames" >&2
fi

resolve_into_set() {
    local domain="$1" addrs
    addrs="$(dig +short +time=3 +tries=2 A "${domain}" | grep -E '^[0-9.]+$' || true)"
    if [[ -z "${addrs}" ]]; then
        echo "init-firewall: WARNING no A record for ${domain}" >&2
        return 0
    fi
    while read -r ip; do
        [[ -z "${ip}" ]] && continue
        ipset add allowed-egress "${ip}" 2>/dev/null || true
    done <<< "${addrs}"
    echo "init-firewall: allowed ${domain}"
}

for domain in "${CLAUDE_DOMAINS[@]}" "${BUILD_DOMAINS[@]}" "${EXTRA_DOMAINS[@]:-}"; do
    [[ -z "${domain}" ]] && continue
    resolve_into_set "${domain//,/}"
done

# Loopback covers Docker's embedded DNS resolver at 127.0.0.11, and nREPL if
# you start one.
iptables -A INPUT  -i lo -j ACCEPT
iptables -A OUTPUT -o lo -j ACCEPT

# Replies to connections we opened. Without this every allowed request hangs.
iptables -A INPUT  -m state --state ESTABLISHED,RELATED -j ACCEPT
iptables -A OUTPUT -m state --state ESTABLISHED,RELATED -j ACCEPT

# DNS has to stay open or nothing resolves after the policy flips.
iptables -A OUTPUT -p udp --dport 53 -j ACCEPT
iptables -A OUTPUT -p tcp --dport 53 -j ACCEPT

# The container's own subnet, so the host can reach forwarded ports. iptables
# applies the mask itself, so the address/prefix pair from `ip` goes in as-is.
if subnet="$(ip -o -f inet addr show eth0 2>/dev/null | awk '{print $4}')" && [[ -n "${subnet}" ]]; then
    iptables -A INPUT  -s "${subnet}" -j ACCEPT
    iptables -A OUTPUT -d "${subnet}" -j ACCEPT
    echo "init-firewall: allowed local subnet ${subnet}"
fi

iptables -A OUTPUT -m set --match-set allowed-egress dst -j ACCEPT

iptables -P INPUT   DROP
iptables -P FORWARD DROP
iptables -P OUTPUT  DROP

echo "init-firewall: egress now default-deny"

# Verify both directions of the claim, so a silently-broken ruleset does not
# read as a working one.
if curl -s -m 5 https://example.com >/dev/null 2>&1; then
    echo "init-firewall: FAILED — example.com is still reachable" >&2
    exit 1
fi
echo "init-firewall: verified — example.com blocked"

# 401 from an unauthenticated GET is a reachable API; only a connection-level
# failure means the rules are wrong.
if ! curl -s -m 10 -o /dev/null https://api.anthropic.com/v1/messages; then
    echo "init-firewall: FAILED — api.anthropic.com is unreachable" >&2
    exit 1
fi
echo "init-firewall: verified — api.anthropic.com reachable"
