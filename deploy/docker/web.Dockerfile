FROM node:24-alpine AS builder

WORKDIR /app
RUN corepack enable
COPY web-admin/package.json web-admin/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY web-admin/ ./
RUN pnpm build

FROM nginxinc/nginx-unprivileged:1.29-alpine

COPY deploy/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html

USER 101
EXPOSE 8080
