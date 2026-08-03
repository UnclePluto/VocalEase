FROM node:24-alpine AS builder

RUN corepack enable

WORKDIR /app

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
COPY apps/admin-web/package.json apps/admin-web/package.json
RUN pnpm install --frozen-lockfile --filter @vocaease/admin-web...

COPY apps/admin-web apps/admin-web
RUN pnpm --filter @vocaease/admin-web build

FROM nginx:1.29-alpine

COPY apps/admin-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/apps/admin-web/dist /usr/share/nginx/html

EXPOSE 80
