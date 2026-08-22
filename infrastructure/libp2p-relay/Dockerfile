# NEO-P2P libp2p Circuit Relay v2
# Lightweight relay for NAT traversal on Indonesian mobile networks
# Arch-agnostic: builds for the target platform via build args (arm64 or amd64)

FROM golang:1.22-alpine AS builder

ARG TARGETOS=linux
ARG TARGETARCH=arm64

RUN apk add --no-cache git ca-certificates

WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .

# Build for the target architecture (Oracle Free Tier ARM64, or amd64 VPS)
RUN CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} \
    go build -ldflags="-w -s" -o /relay .

# Minimal runtime — 15MB binary, Alpine base
FROM alpine:3.19
RUN apk add --no-cache ca-certificates && \
    adduser -D relayuser

COPY --from=builder /relay /relay
USER relayuser

EXPOSE 4001 4002

ENTRYPOINT ["/relay"]
