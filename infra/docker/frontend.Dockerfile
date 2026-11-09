# The React web app: built by Vite, served by nginx on port 5173 (the origin Keycloak's web-app client and the Gateway's CORS rule name).
# ---- Stage 1 (build): Node compiles the TypeScript and bundles the app with Vite into plain files (HTML, JS, CSS).
FROM node:22-alpine AS build
WORKDIR /app
# Dependency lists first for the Docker cache (see gateway.Dockerfile). `npm ci` installs exactly the locked versions.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend .
# These are compiled into the app and are public by nature (the browser needs them): the defaults are the local Compose addresses.
ARG VITE_KEYCLOAK_URL=http://localhost:8180
ARG VITE_GATEWAY_URL=http://localhost:8080
ARG VITE_SUPPORT_EMAIL=support@example.invalid
# Vite copies variables that start with VITE_ into the bundle at build time. They end up in the browser, so they must hold no secret.
ENV VITE_KEYCLOAK_URL=$VITE_KEYCLOAK_URL VITE_GATEWAY_URL=$VITE_GATEWAY_URL VITE_SUPPORT_EMAIL=$VITE_SUPPORT_EMAIL
RUN npm run build

# ---- Stage 2 (run): nginx serves the finished files. Node is not in the final image.
FROM nginx:1.27-alpine
# default.conf is the file nginx loads for its default server, so this replaces the stock configuration.
COPY infra/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
# EXPOSE only documents the port; the real publishing is the `ports` entry in docker-compose.yml.
EXPOSE 5173
