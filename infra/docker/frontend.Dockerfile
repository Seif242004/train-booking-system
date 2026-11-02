# The React web app: built by Vite, served by nginx on port 5173 (the origin Keycloak's web-app client and the Gateway's CORS rule name).
FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend .
# These are compiled into the app and are public by nature (the browser needs them): the defaults are the local Compose addresses.
ARG VITE_KEYCLOAK_URL=http://localhost:8180
ARG VITE_GATEWAY_URL=http://localhost:8080
ARG VITE_SUPPORT_EMAIL=support@example.invalid
ENV VITE_KEYCLOAK_URL=$VITE_KEYCLOAK_URL VITE_GATEWAY_URL=$VITE_GATEWAY_URL VITE_SUPPORT_EMAIL=$VITE_SUPPORT_EMAIL
RUN npm run build

FROM nginx:1.27-alpine
COPY infra/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 5173
