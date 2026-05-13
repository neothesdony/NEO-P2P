# NEO-P2P libp2p Circuit Relay v2
# Lightweight relay for NAT traversal on Indonesian mobile networks
# Deployed on Oracle Free Tier ARM64

FROM golang:1.22-alpine AS builder

RUN apk add --no-cache git ca-certificates

WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .

# Build for ARM64 (Oracle Free Tier)
RUN CGO_ENABLED=0 GOOS=linux GOARCH=arm64 \
    go build -ldflags="-w -s" -o /relay .

# Minimal runtime — 15MB binary, Alpine base
FROM alpine:3.19
RUN apk add --no-cache ca-certificates && \
    adduser -D relayuser

COPY --from=builder /relay /relay
USER relayuser

EXPOSE 4001 4002

ENTRYPOINT ["/relay"]
