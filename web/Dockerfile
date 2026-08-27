# Build stage: static Vite build. VITE_GRAPHQL_URL is baked in at build time
# (Vite env vars are compile-time, not runtime) — docker-compose.yml passes
# it as a plain environment var here for local `docker build`/`docker-compose
# build` convenience; a real deployment would pass it as a build arg instead.
FROM node:26-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_GRAPHQL_URL=http://localhost:8080/graphql
ENV VITE_GRAPHQL_URL=${VITE_GRAPHQL_URL}
RUN npm run build

# Runtime stage: static file serving only.
FROM nginx:alpine
# CVE-2026-14456 (OpenSSL DoS via unbounded memory): the nginx:alpine base
# image ships libcrypto3 3.5.7-r0, patched in 3.5.8-r0, which is already in
# Alpine's package index but not yet rebuilt into the published base image.
# Force the upgrade explicitly rather than waiting on an upstream rebuild.
RUN apk update && apk upgrade --no-cache libcrypto3 libssl3
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
