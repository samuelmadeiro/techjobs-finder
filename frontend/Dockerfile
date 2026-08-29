# Build
FROM node:22-alpine AS build
WORKDIR /app

# Camada de dependências: só reexecuta quando o manifesto muda.
COPY package.json package-lock.json* ./
RUN npm install --no-audit --no-fund

COPY tsconfig.json vite.config.ts index.html ./
COPY src ./src

# Vazio = chamadas relativas passando pelo proxy /api do nginx. Preencha apenas quando
# o backend estiver em outro host, pois o valor é embutido no bundle em build time.
ARG VITE_API_URL=""
ENV VITE_API_URL=$VITE_API_URL
RUN npm run build

# Runtime
FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8081

# 127.0.0.1 e não localhost: o nginx escuta só em IPv4, e "localhost" resolve ::1 primeiro
# dentro do contêiner, o que faria o healthcheck falhar com o servidor no ar.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
    CMD wget -qO- http://127.0.0.1:8081/health || exit 1
