# The API Gateway (Express). Build from the repository root (docker-compose.yml does this).
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY gateway/package.json gateway/package-lock.json ./
RUN npm ci --omit=dev
COPY gateway/src src
USER node
# HOST=0.0.0.0 is set by Compose: inside the container the Gateway must listen on all interfaces (Compose publishes the port on loopback only).
CMD ["node", "src/server.js"]
