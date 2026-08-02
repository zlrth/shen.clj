# JDK 21 and 25 are the two versions .github/workflows/ci.yml tests. 21 is the
# default here so the container sits on the lower bound of what CI proves;
# build with --build-arg JDK_VERSION=25 to check the upper one.
ARG JDK_VERSION=21
FROM clojure:temurin-${JDK_VERSION}-tools-deps-bookworm

# iptables and ipset back init-firewall.sh. ripgrep is what Claude Code reaches
# for instead of shelling out to grep; dnsutils gives the firewall script dig.
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates \
      curl \
      dnsutils \
      git \
      iproute2 \
      ipset \
      iptables \
      jq \
      less \
      procps \
      ripgrep \
      sudo \
      unzip \
 && rm -rf /var/lib/apt/lists/*

ARG USERNAME=dev
ARG USER_UID=1000
ARG USER_GID=1000
RUN groupadd --gid ${USER_GID} ${USERNAME} \
 && useradd --uid ${USER_UID} --gid ${USER_GID} --create-home --shell /bin/bash ${USERNAME}

# The firewall is the one thing that needs root, and it is the only thing the
# sudoers entry grants. Deliberately not blanket NOPASSWD: an agent that can
# sudo freely can flush the egress rules, which is the whole point of them.
# EXTRA_ALLOWED_DOMAINS needs env_keep to reach the script: sudo's env_reset
# drops it otherwise, and `sudo -E` is refused without SETENV. env_keep is the
# narrower of the two — it preserves that one variable rather than handing the
# caller the whole environment.
COPY init-firewall.sh /usr/local/bin/init-firewall.sh
RUN chmod 0755 /usr/local/bin/init-firewall.sh \
 && printf '%s\n' \
      'Defaults env_keep += "EXTRA_ALLOWED_DOMAINS"' \
      "${USERNAME} ALL=(root) NOPASSWD: /usr/local/bin/init-firewall.sh" \
      > /etc/sudoers.d/init-firewall \
 && chmod 0440 /etc/sudoers.d/init-firewall

# Volume mount points, created here so that Docker seeds the named volumes from
# an image path already owned by ${USERNAME}. A mount point missing from the
# image gets a volume owned by root instead, and the kernel render then fails
# on its own .mkdirs of target/generated.
RUN mkdir -p /home/${USERNAME}/.m2 \
             /home/${USERNAME}/.gitlibs \
             /home/${USERNAME}/.claude \
             /workspace/.cpcache \
             /workspace/target \
 && chown -R ${USER_UID}:${USER_GID} /home/${USERNAME} /workspace

USER ${USERNAME}
WORKDIR /workspace

# The native installer. No Node runtime is involved.
RUN curl -fsSL https://claude.ai/install.sh | bash
ENV PATH="/home/${USERNAME}/.local/bin:${PATH}"

# Claude Code keeps its state in two places: the ~/.claude directory (sessions,
# projects, downloads) and ~/.claude.json (onboarding, theme, per-project
# trust). The second lives at the home root, *outside* the volume mounted at
# ~/.claude, so on a --rm run it is always missing and the first-run wizard
# opens. That wizard asks you to pick a login method even when
# CLAUDE_CODE_OAUTH_TOKEN is set — it never consults the variable. Pointing
# CLAUDE_CONFIG_DIR at the mounted directory puts .claude.json inside the
# volume, so onboarding is answered once and survives the container.
ENV CLAUDE_CONFIG_DIR="/home/${USERNAME}/.claude"

# The answers themselves, merged into the volume's .claude.json at every start
# by init-claude-config. Kept outside ${CLAUDE_CONFIG_DIR} on purpose: a file
# written *into* the mount point only ever reaches an empty volume, which is
# exactly the case that stops holding after your first run.
#
# bypassPermissionsModeAccepted pre-answers the warning this directory exists to
# make defensible; drop the key from claude-onboarding.json to be asked instead.
COPY --chown=${USER_UID}:${USER_GID} claude-onboarding.json /usr/local/share/claude-onboarding.json
COPY --chown=${USER_UID}:${USER_GID} init-claude-config.sh /usr/local/bin/init-claude-config

# Chains to the base image's entrypoint, so `docker run … bash -c …` and the
# inherited `clj` dispatch both behave exactly as before.
USER root
RUN chmod 0755 /usr/local/bin/init-claude-config \
 && chmod 0644 /usr/local/share/claude-onboarding.json
USER ${USERNAME}
ENTRYPOINT ["/usr/local/bin/init-claude-config"]

# Every alias in deps.edn already sets -Xss16m; JAVA_TOOL_OPTIONS is left unset
# on purpose, since it writes a "Picked up ..." line to stderr that ends up in
# the logs .github/ci-summary.sh parses.
