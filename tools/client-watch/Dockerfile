FROM golang:1.22-alpine AS build
WORKDIR /src
RUN apk add --no-cache ca-certificates
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /out/rpcnode-client-watch .

FROM alpine:3.20
RUN apk add --no-cache ca-certificates curl
WORKDIR /data/clients
COPY --from=build /out/rpcnode-client-watch /usr/local/bin/rpcnode-client-watch
EXPOSE 8094
HEALTHCHECK --interval=20s --timeout=3s --start-period=5s --retries=3 \
  CMD curl -fsS -o /dev/null http://127.0.0.1:8094/healthz || exit 1
ENTRYPOINT ["/usr/local/bin/rpcnode-client-watch"]
