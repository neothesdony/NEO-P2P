FROM golang:1.22-alpine AS builder
ARG TARGETOS=linux
ARG TARGETARCH=arm64
WORKDIR /build
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} go build -o ws-relay .

FROM alpine:3.19
RUN apk add --no-cache ca-certificates
COPY --from=builder /build/ws-relay /usr/local/bin/ws-relay
EXPOSE 4003
ENTRYPOINT ["ws-relay"]
