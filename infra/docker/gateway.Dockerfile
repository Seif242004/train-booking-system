# The API Gateway (Express). Build from the repository root (docker-compose.yml does this).
# alpine = a very small Linux base. Node 22 is the LTS line used for the Gateway.
FROM node:22-alpine
WORKDIR /app
# Libraries (Express) switch to their faster production mode, and `npm` skips development packages.
ENV NODE_ENV=production
# Copy the dependency lists first so that `npm ci` is cached until a dependency changes. `npm ci` installs exactly what the lock file says;
# --omit=dev leaves out the test tools.
COPY gateway/package.json gateway/package-lock.json ./
RUN npm ci --omit=dev
# Only src/ is copied: the tests and the README are not needed at run time.
COPY gateway/src src
# Run as the unprivileged "node" user that the base image provides, not as root.
USER node
# HOST=0.0.0.0 is set by Compose: inside the container the Gateway must listen on all interfaces (Compose publishes the port on
# loopback only).
CMD ["node", "src/server.js"]
