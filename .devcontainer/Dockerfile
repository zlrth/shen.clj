# JDK 21 and 25 are the two versions .github/workflows/ci.yml tests. 21 is the
# default here so the container sits on the lower bound of what CI proves;
# build with --build-arg JDK_VERSION=25 to check the upper one.
#
# Everything Claude-Code- and firewall-shaped used to live here and now comes
# from the claude-sandbox feature (see devcontainer.json). What remains is
# only what is this repo's own: the toolchain image, the user, and the mount
# points for the project's cache volumes.
ARG JDK_VERSION=21
FROM clojure:temurin-${JDK_VERSION}-tools-deps-bookworm

ARG USERNAME=dev
ARG USER_UID=1000
ARG USER_GID=1000
RUN groupadd --gid ${USER_GID} ${USERNAME} \
 && useradd --uid ${USER_UID} --gid ${USER_GID} --create-home --shell /bin/bash ${USERNAME}

# Volume mount points, created here so that Docker seeds the named volumes from
# an image path already owned by ${USERNAME}. A mount point missing from the
# image gets a volume owned by root instead, and the kernel render then fails
# on its own .mkdirs of target/generated.
RUN mkdir -p /home/${USERNAME}/.m2 \
             /home/${USERNAME}/.gitlibs \
             /workspace/.cpcache \
             /workspace/target \
 && chown -R ${USER_UID}:${USER_GID} /home/${USERNAME} /workspace

USER ${USERNAME}
WORKDIR /workspace

# Every alias in deps.edn already sets -Xss16m; JAVA_TOOL_OPTIONS is left unset
# on purpose, since it writes a "Picked up ..." line to stderr that ends up in
# the logs .github/ci-summary.sh parses.
