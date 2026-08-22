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
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
