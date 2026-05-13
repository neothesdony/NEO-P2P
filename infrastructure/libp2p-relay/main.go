package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/protocol"
	"github.com/libp2p/go-libp2p/p2p/host/autonat"
	"github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/relay"
	"github.com/libp2p/go-libp2p/p2p/protocol/holepunch"
	"github.com/multiformats/go-multiaddr"
)

func main() {
	relayPort := flag.Int("relay-port", 4001, "Circuit relay listen port")
	advertisePort := flag.Int("advertise-port", 4002, "Health/advertise port")
	dataDir := flag.String("data-dir", "/data", "Persistent data directory")
	flag.Parse()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// ── Load or generate persistent identity ──
	keyPath := *dataDir + "/relay.key"
	privKey := loadOrCreateKey(keyPath)
	peerID, _ := peer.IDFromPrivateKey(privKey)
	log.Printf("Relay PeerID: %s", peerID.String())

	// ── Build relay host ──
	relayAddr := fmt.Sprintf("/ip4/0.0.0.0/tcp/%d", *relayPort)

	host, err := libp2p.New(
		libp2p.Identity(privKey),
		libp2p.ListenAddrStrings(relayAddr),
		libp2p.EnableAutoRelay(),
		libp2p.EnableHolePunching(),
		libp2p.NATPortMap(),
		libp2p.AutoNATServiceRateLimit(10, 5, time.Minute),
		libp2p.ResourceManager(&libp2p.BasicResourceManager{
			LimitConfig: libp2p.ResourceLimits{
				MaxConnsIn:    1000,
				MaxConnsOut:   1000,
				MaxConns:      2000,
				MaxFD:         5000,
				MaxMemory:     256 << 20, // 256MB memory limit
			},
		}),
	)
	if err != nil {
		log.Fatalf("Failed to create libp2p host: %v", err)
	}
	defer host.Close()

	// ── Enable Circuit Relay v2 ──
	_, err = relay.New(host, relay.WithResources(relay.Resources{
		MaxReservations:        512,
		MaxCircuits:            256,
		MaxCircuitsPerPeer:     8,
		MaxReservationsPerPeer: 16,
		BufferSize:             4096,
	}))
	if err != nil {
		log.Fatalf("Failed to enable circuit relay: %v", err)
	}
	log.Printf("Circuit Relay v2 enabled — max 512 reservations, 256 concurrent circuits")

	// ── AutoNAT for public address detection ──
	autoNat, err := autonat.New(host, autonat.WithReachability(autonat.ReachabilityPublic))
	if err != nil {
		log.Printf("Warning: AutoNAT init failed: %v", err)
	} else {
		log.Printf("AutoNAT enabled — reachability: %v", autoNat.Status())
	}

	// ── Print addresses ──
	for _, addr := range host.Addrs() {
		log.Printf("Listening: %s/p2p/%s", addr, peerID.String())
	}

	// ── Health endpoint ──
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"status":"ok","peerID":"%s","connections":%d,"circuits":%d}`,
			peerID.String(), len(host.Network().Conns()), relay.NumActiveCircuits())
	})
	mux.HandleFunc("/peers", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		peers := host.Network().Peers()
		fmt.Fprintf(w, `{"connected_peers":%d}`, len(peers))
	})

	healthServer := &http.Server{
		Addr:    fmt.Sprintf(":%d", *advertisePort),
		Handler: mux,
	}
	go func() {
		log.Printf("Health endpoint on :%d", *advertisePort)
		if err := healthServer.ListenAndServe(); err != http.ErrServerClosed {
			log.Printf("Health server error: %v", err)
		}
	}()

	// ── Graceful shutdown ──
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	<-sigCh

	log.Println("Shutting down...")
	healthServer.Shutdown(ctx)
	host.Close()
}

func loadOrCreateKey(path string) crypto.PrivKey {
	if data, err := os.ReadFile(path); err == nil {
		privKey, err := crypto.UnmarshalPrivateKey(data)
		if err == nil {
			log.Printf("Loaded existing relay key from %s", path)
			return privKey
		}
		log.Printf("Corrupted key file, generating new: %v", err)
	}

	privKey, _, err := crypto.GenerateEd25519Key(rand.Reader)
	if err != nil {
		log.Fatalf("Failed to generate key: %v", err)
	}

	data, err := crypto.MarshalPrivateKey(privKey)
	if err != nil {
		log.Fatalf("Failed to marshal key: %v", err)
	}

	os.MkdirAll(*dataDir, 0700)
	if err := os.WriteFile(path, data, 0600); err != nil {
		log.Printf("Warning: Could not persist key: %v", err)
	}

	log.Printf("Generated new relay key, peerID will be derived")
	return privKey
}
