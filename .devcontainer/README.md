# Dev container

A container for running Claude Code against this repo with a bounded blast
radius. The sandbox itself — Claude Code, the default-deny egress firewall,
persistent auth/state, scoped sudo — comes from the
[`claude-sandbox` feature](https://github.com/zlrth/devcontainer-features/tree/main/src/claude-sandbox);
this directory only adds what is shen.clj's own: the JDK/Clojure image, the
cache volumes, and the Maven/Clojars allowlist. See the feature's README for
the full design notes and troubleshooting.

The seeded default permission mode is **acceptEdits** — edits are
auto-approved, commands still ask. The sandbox is what makes
`bypassPermissions` defensible, but it's an opt-in
(`"permissionMode": "bypassPermissions", "acceptBypassPermissions": true` on
the feature), not the default.

## What it protects, and what it doesn't

- **Writes** — bounded by the container. On macOS Docker is a VM, so this is
  kernel-level isolation, not a seatbelt profile.
- **Egress** — bounded by the feature's firewall: `OUTPUT` defaults to DROP,
  and only Anthropic's hosts, GitHub, Maven Central, and Clojars are allowed.

Not protected:

- **Your working tree.** `/workspace` is a bind mount of the real repo, so an
  `rm -rf` in there is an `rm -rf` on your disk. Commit or push before an
  unattended run, or mount a throwaway clone.
- **Anything you allowlist.** Opening a domain opens it for everything in the
  container.

## Quick start

Authenticate on the host first. Do not mount `~/.claude` as a substitute — on
macOS the OAuth credentials live in the Keychain, not in that directory:

```bash
claude setup-token                      # on the host
export CLAUDE_CODE_OAUTH_TOKEN=...      # devcontainer.json forwards this
```

Then open the repo in VS Code and reopen in container, or from the CLI:

```bash
npx @devcontainers/cli up --workspace-folder .
npx @devcontainers/cli exec --workspace-folder . claude
```

Allow more domains for one session with
`EXTRA_ALLOWED_DOMAINS="a.example b.example" sudo /usr/local/bin/init-firewall.sh`,
or permanently via the feature's `allowedDomains` option in
`devcontainer.json`.

## Volumes

| Volume | Mount | Why |
| --- | --- | --- |
| `shen-clj-m2` | `/home/dev/.m2` | Maven resolution across Docker Desktop's file sharing is slow; these are the paths CI caches |
| `shen-clj-gitlibs` | `/home/dev/.gitlibs` | as above, for git deps |
| `shen-clj-cpcache` | `/workspace/.cpcache` | **must** be isolated — see below |
| `shen-clj-target` | `/workspace/target` | keeps container and host builds from overwriting each other's render |

Claude Code's own state lives in a volume the feature manages (keyed by
devcontainer, mounted at `/claude-state`), which is what makes auth and
onboarding survive `--rm` runs.

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
`--build-arg JDK_VERSION=25` for the other.

Worth noting: the host toolchain here is GraalVM 23, which is neither of the
versions CI covers. The container is the easy way onto a JDK you actually test.
