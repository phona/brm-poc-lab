FROM golang:1.22-alpine AS build
WORKDIR /src
ENV GOTOOLCHAIN=auto
RUN apk add --no-cache git
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go run github.com/swaggo/swag/cmd/swag@v1.16.3 init -g main.go -o docs --parseInternal
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /gateway .

FROM alpine:3.19
RUN apk add --no-cache ca-certificates
WORKDIR /app
COPY --from=build /gateway /app/gateway
EXPOSE 8080
ENTRYPOINT ["/app/gateway"]
