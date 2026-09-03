# syntax=docker/dockerfile:1

FROM node:22-bookworm-slim AS builder
WORKDIR /app
COPY admin/package.json admin/package-lock.json ./
RUN npm ci
COPY admin/ ./
# The container serves the SPA and API from one origin through nginx.
RUN printf 'VITE_API_URL=\n' > .env.production && npm run build

FROM nginx:1.27-alpine
COPY admin/nginx.docker.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 8080
