# Dev container

A container for running Claude Code against this repo with a bounded blast
radius, so that `--dangerously-skip-permissions` is a defensible thing to type.

The toolchain is JDK plus the Clojure CLI and nothing else, which is why this
ports cleanly — `.github/workflows/ci.yml` already runs the whole suite on
`ubuntu-latest`.

## What it protects, and what it doesn't

Two separate things are contained here:

- **Writes** — bounded by the container. On macOS Docker is a VM, so this is
  kernel-level isolation, not a seatbelt profile.
- **Egress** — bounded by `init-firewall.sh`, which sets `OUTPUT` to default
  DROP and allowlists only what this repo and Claude Code actually need.

The second one is the part that is easy to skip and shouldn't be. A container
with open networking has contained the filesystem but not the data: anything
readable is still exfiltratable.

What it does **not** protect:

- **Your working tree.** `/workspace` is a bind mount of the real repo, so an
  `rm -rf` in there is an `rm -rf` on your disk. Commit or push before an
  unattended run, or mount a throwaway clone.
- **Anything you allowlist.** Opening a domain opens it for everything in the
  container.

## Quick start

Authenticate on the host first. Do not mount `~/.claude` as a substitute — on
macOS the OAuth credentials live in the Keychain, not in that directory, so the
mount would carry your session history in without carrying auth:

```bash
claude setup-token                      # on the host
export CLAUDE_CODE_OAUTH_TOKEN=...      # devcontainer.json forwards this
```

The token alone is not sufficient, and the failure is confusing: the first-run
wizard asks you to pick a login method *without ever consulting
`CLAUDE_CODE_OAUTH_TOKEN`*, so a correctly-forwarded token still lands you on a
sign-in screen. The wizard runs whenever `.claude.json` is absent, and that file
lives at the home root rather than in `~/.claude` — outside the state volume,
therefore missing on every `--rm` run. The Dockerfile fixes this by setting
`CLAUDE_CONFIG_DIR=/home/dev/.claude`, which moves `.claude.json` into the
volume, and `init-claude-config`, which merges the onboarding answers into that
file at every start. See [Troubleshooting](#troubleshooting) if you meet it
anyway.

Merging at *every* start rather than seeding the image once is deliberate.
Docker copies image content into a named volume only when the volume is empty,
so anything baked in reaches a first run and nothing after it — and a
`shen-clj-claude-state` from an earlier build would go on producing the login
screen no matter how many times you rebuilt. Existing keys win the merge, so a
theme you changed or a path you trusted is never undone.

```bash
docker build --build-arg JDK_VERSION=21 -t shen-clj-dev:21 .devcontainer

docker run -it --rm \
  --cap-add=NET_ADMIN --cap-add=NET_RAW --memory=8g \
  -v "$PWD":/workspace \
  -v shen-clj-m2:/home/dev/.m2 \
  -v shen-clj-gitlibs:/home/dev/.gitlibs \
  -v shen-clj-cpcache:/workspace/.cpcache \
  -v shen-clj-target:/workspace/target \
  -v shen-clj-claude-state:/home/dev/.claude \
  -e CLAUDE_CODE_OAUTH_TOKEN \
  shen-clj-dev:21 \
  bash -c 'sudo /usr/local/bin/init-firewall.sh && exec claude --dangerously-skip-permissions'
```

Note that `--cap-drop=ALL` is *not* used. `sudo` needs `SETUID`/`SETGID`, and
dropping them takes `init-firewall.sh` down with it.

## The firewall

Allowed by default:

| Host | Why |
| --- | --- |
| `api.anthropic.com` | the API, and the telemetry that rides on it |
| `claude.ai` | installer, artifact publishing |
| GitHub published ranges, from `api.github.com/meta` | `io.github.cognitect-labs/test-runner` is a git dep |
| `codeload.github.com`, `objects.githubusercontent.com`, `raw.githubusercontent.com` | belt and braces over the ranges above |
| `repo1.maven.org`, `repo.maven.apache.org` | Maven Central |
| `repo.clojars.org` | Clojars |

Add more for a session with `EXTRA_ALLOWED_DOMAINS="a.example b.example"`.

The script verifies both directions before it exits — that `example.com` is
blocked *and* that `api.anthropic.com` is reachable — so a silently-broken
ruleset can't read as a working one.

Two things worth knowing:

- Allowlisting is **by resolved IP, captured at start**. The CDNs behind Maven
  Central and GitHub rotate, so a container left up for days may start seeing
  drops. Re-run the script to refresh.
- `sudo` is scoped to `init-firewall.sh` alone, deliberately. An agent with
  blanket `sudo` can flush the egress rules, which would defeat the point.

## Volumes

| Volume | Mount | Why |
| --- | --- | --- |
| `shen-clj-m2` | `/home/dev/.m2` | Maven resolution across Docker Desktop's file sharing is slow; these are the paths CI caches |
| `shen-clj-gitlibs` | `/home/dev/.gitlibs` | as above, for git deps |
| `shen-clj-cpcache` | `/workspace/.cpcache` | **must** be isolated — see below |
| `shen-clj-target` | `/workspace/target` | keeps container and host builds from overwriting each other's render |
| `shen-clj-claude-state` | `/home/dev/.claude` | Claude Code state — sessions, and (via `CLAUDE_CONFIG_DIR`) the `.claude.json` that records onboarding |

`.cpcache` is not optional. The Clojure CLI always prefers `./.cpcache` over
`CLJ_CACHE` when the project directory is writable, and it caches *absolute*
classpaths — the host's entries point at `/Users/...`, which does not exist in
the container.

Every mount point is created in the Dockerfile before its volume is attached.
Docker seeds a named volume from the image path underneath it; a mount point
missing from the image gets a root-owned volume instead, and the kernel render
then fails on its own `.mkdirs` of `target/generated`.

To force the from-scratch render CI deliberately never caches:

```bash
docker volume rm shen-clj-target
```

## JDKs

CI tests 21 and 25. The container defaults to 21; rebuild with
`--build-arg JDK_VERSION=25` for the other. Both were verified with the full
suite — 11 Clojure tests / 50 assertions, 134 kernel, 8 extension, zero
failures, from a clean `target/` and through the firewall.

Worth noting: the host toolchain here is GraalVM 23, which is neither of the
versions CI covers. The container is the easy way onto a JDK you actually test.

## Troubleshooting

**It asks me to log in anyway.** `init-claude-config` should make this
impossible — check it ran and what it produced:

```bash
docker run --rm -v shen-clj-claude-state:/state --entrypoint bash shen-clj-dev:21 \
  -c 'jq "{hasCompletedOnboarding, theme, projects}" /state/.claude.json'
```

`hasCompletedOnboarding: true` there and a login screen anyway means the volume
is not the one the container reads; anything else means the merge did not run.
Recreating the volume is the blunt fix, at the cost of the session history in
it:

```bash
docker volume rm shen-clj-claude-state
```

If that isn't it, check the token actually crossed the boundary. `-e VAR` with
no value forwards nothing when `VAR` is set but not exported, and `echo $VAR`
in the shell can't tell the two apart:

```bash
docker run --rm -e CLAUDE_CODE_OAUTH_TOKEN shen-clj-dev:21 \
  bash -c 'echo "${CLAUDE_CODE_OAUTH_TOKEN:-<EMPTY>}"'
```

A token that arrives but is wrong fails loudly rather than silently — headless
mode says `401 OAuth access token is invalid`, no sign-in screen. Note that a
Console API key (`sk-ant-api03-…`) is not an OAuth token (`sk-ant-oat01-…`);
the key belongs in `ANTHROPIC_API_KEY`, and only `claude setup-token` produces
the other.

**I want to `/login` from inside the container.** The default allowlist doesn't
cover the OAuth endpoints, on the principle that the token is the supported
path in here. To open them for a session:

```bash
EXTRA_ALLOWED_DOMAINS="claude.com platform.claude.com" \
  sudo /usr/local/bin/init-firewall.sh
```
