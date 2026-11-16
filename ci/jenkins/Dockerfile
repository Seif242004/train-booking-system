# Jenkins for the local CI/CD pipeline: LTS with JDK 21 (the project's Java), plus the tools the pipeline calls, all pinned.
# The Docker CLI talks to the host's Docker through the mounted socket; kubectl talks to the kind cluster; kind is only here so
# that ci.mjs can create the cluster without installing anything on the host. x86_64 Linux only.
FROM jenkins/jenkins:2.580.2-lts-jdk21
USER root
ARG DOCKER_VERSION=29.8.2
ARG BUILDX_VERSION=v0.39.0
ARG KUBECTL_VERSION=v1.37.2
ARG KIND_VERSION=v0.33.0
ARG NODE_VERSION=v22.23.5
RUN apt-get update && apt-get install -y --no-install-recommends xz-utils && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_VERSION}.tgz | tar -xz --strip-components=1 -C /usr/local/bin docker/docker \
 && mkdir -p /usr/local/lib/docker/cli-plugins \
 && curl -fsSL -o /usr/local/lib/docker/cli-plugins/docker-buildx https://github.com/docker/buildx/releases/download/${BUILDX_VERSION}/buildx-${BUILDX_VERSION}.linux-amd64 \
 && chmod +x /usr/local/lib/docker/cli-plugins/docker-buildx \
 && curl -fsSL -o /usr/local/bin/kubectl https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl \
 && curl -fsSL -o /usr/local/bin/kind https://github.com/kubernetes-sigs/kind/releases/download/${KIND_VERSION}/kind-linux-amd64 \
 && chmod +x /usr/local/bin/kubectl /usr/local/bin/kind \
 && curl -fsSL https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-x64.tar.xz | tar -xJ --strip-components=1 -C /usr/local \
 # The repository may be mounted read-only from the host with another owner: let git read it.
 && git config --system --add safe.directory '*'
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
