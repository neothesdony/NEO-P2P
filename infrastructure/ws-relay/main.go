package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"golang.org/x/net/websocket"
)

// ─── Protocol ───────────────────────────────────────────────

type Message struct {
	Type    string   `json:"type"`    // announce, send, subscribe, publish, find_peer, peer_list, message, error
	PeerID  string   `json:"peerId,omitempty"`
	From    string   `json:"from,omitempty"`
	To      string   `json:"to,omitempty"`
	Topic   string   `json:"topic,omitempty"`
	MsgType string   `json:"msgType,omitempty"`
	Data    string   `json:"data,omitempty"`
	Peers   []string `json:"peers,omitempty"`
	Message string   `json:"message,omitempty"`
}

// ─── Peer Registry ──────────────────────────────────────────

type Peer struct {
	ID        string
	Conn      *websocket.Conn
	Connected time.Time
	mu        sync.Mutex
}

type Relay struct {
	mu       sync.RWMutex
	peers    map[string]*Peer
	topics   map[string]map[string]*Peer // topic -> peerID -> Peer
}

func NewRelay() *Relay {
	return &Relay{
		peers:  make(map[string]*Peer),
		topics: make(map[string]map[string]*Peer),
	}
}

func (r *Relay) Register(peerID string, conn *websocket.Conn) {
	r.mu.Lock()
	defer r.mu.Unlock()

	// Close existing connection if peer reconnects
	if old, ok := r.peers[peerID]; ok {
		old.Conn.Close()
	}

	r.peers[peerID] = &Peer{
		ID:        peerID,
		Conn:      conn,
		Connected: time.Now(),
	}
	log.Printf("Peer registered: %s (total: %d)", peerID, len(r.peers))
}

func (r *Relay) Unregister(peerID string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	delete(r.peers, peerID)

	// Remove from all topics
	for topic, subscribers := range r.topics {
		delete(subscribers, peerID)
		if len(subscribers) == 0 {
			delete(r.topics, topic)
		}
	}
	log.Printf("Peer unregistered: %s (total: %d)", peerID, len(r.peers))
}

func (r *Relay) SendToPeer(to string, msg Message) error {
	r.mu.RLock()
	peer, ok := r.peers[to]
	r.mu.RUnlock()

	if !ok {
		return fmt.Errorf("peer not found: %s", to)
	}

	peer.mu.Lock()
	defer peer.mu.Unlock()

	return websocket.JSON.Send(peer.Conn, msg)
}

func (r *Relay) Subscribe(peerID, topic string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.topics[topic] == nil {
		r.topics[topic] = make(map[string]*Peer)
	}

	if peer, ok := r.peers[peerID]; ok {
		r.topics[topic][peerID] = peer
		log.Printf("Peer %s subscribed to topic: %s", peerID, topic)
	}
}

func (r *Relay) Publish(topic string, msg Message, senderID string) {
	r.mu.RLock()
	subscribers := r.topics[topic]
	r.mu.RUnlock()

	for id, peer := range subscribers {
		if id == senderID {
			continue // Don't echo back to sender
		}
		peer.mu.Lock()
		websocket.JSON.Send(peer.Conn, msg)
		peer.mu.Unlock()
	}
}

func (r *Relay) PeerList() []string {
	r.mu.RLock()
	defer r.mu.RUnlock()

	peers := make([]string, 0, len(r.peers))
	for id := range r.peers {
		peers = append(peers, id)
	}
	return peers
}

// ─── WebSocket Handler ───────────────────────────────────────

// handleWebSocket returns an http.Handler that upgrades to a WebSocket
// connection. It uses websocket.Server with a permissive Handshake so that
// clients (e.g. the Android Ktor client) that do not send a matching Origin
// header are not rejected with 403. The deprecated websocket.Handler enforces
// an Origin==Host check that breaks non-browser clients.
func handleWebSocket(relay *Relay) http.Handler {
	return &websocket.Server{
		Handshake: func(*websocket.Config, *http.Request) error {
			return nil // allow any origin
		},
		Handler: func(conn *websocket.Conn) {
			var peerID string
			defer func() {
				if peerID != "" {
					relay.Unregister(peerID)
				}
				conn.Close()
			}()

			// Set read/write deadlines
			conn.SetDeadline(time.Now().Add(5 * time.Minute))

			for {
				var msg Message
				if err := websocket.JSON.Receive(conn, &msg); err != nil {
					if peerID != "" {
					log.Printf("Peer %s disconnected: %v", peerID, err)
				}
				return
			}

			switch msg.Type {
			case "announce":
				peerID = msg.PeerID
				if peerID == "" {
					websocket.JSON.Send(conn, Message{Type: "error", Message: "peerId required"})
					continue
				}
				relay.Register(peerID, conn)

				// Send current peer list
				websocket.JSON.Send(conn, Message{
					Type:  "peer_list",
					Peers: relay.PeerList(),
				})

			case "send":
				if peerID == "" {
					websocket.JSON.Send(conn, Message{Type: "error", Message: "not announced"})
					continue
				}
				if msg.To == "" {
					websocket.JSON.Send(conn, Message{Type: "error", Message: "recipient required"})
					continue
				}
				err := relay.SendToPeer(msg.To, Message{
					Type:    "message",
					From:    peerID,
					MsgType: msg.MsgType,
					Data:    msg.Data,
				})
				if err != nil {
					websocket.JSON.Send(conn, Message{
						Type:    "error",
						Message: fmt.Sprintf("delivery failed: %v", err),
					})
				}

			case "subscribe":
				if peerID == "" {
					websocket.JSON.Send(conn, Message{Type: "error", Message: "not announced"})
					continue
				}
				if msg.Topic == "" {
					websocket.JSON.Send(conn, Message{Type: "error", Message: "topic required"})
					continue
				}
				relay.Subscribe(peerID, msg.Topic)

			case "publish":
				if peerID == "" {
					websocket.JSON.Send(conn, Message{Type: "error", Message: "not announced"})
					continue
				}
				if msg.Topic == "" {
					websocket.JSON.Send(conn, Message{Type: "error", Message: "topic required"})
					continue
				}
				relay.Publish(msg.Topic, Message{
					Type:    "message",
					From:    peerID,
					MsgType: msg.MsgType,
					Data:    msg.Data,
				}, peerID)

			case "find_peer":
				if peerID == "" {
					websocket.JSON.Send(conn, Message{Type: "error", Message: "not announced"})
					continue
				}
				// Respond with current peer list
				websocket.JSON.Send(conn, Message{
					Type:  "peer_list",
					Peers: relay.PeerList(),
				})

			default:
				websocket.JSON.Send(conn, Message{
					Type:    "error",
					Message: fmt.Sprintf("unknown message type: %s", msg.Type),
				})
			}
		}
		},
	}
}

// ─── Health Handler ─────────────────────────────────────────

func healthHandler(relay *Relay) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		peers := relay.PeerList()
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":  "ok",
			"peers":   len(peers),
			"uptime":  time.Since(startTime).String(),
		})
	}
}

var startTime time.Time

// ─── Main ───────────────────────────────────────────────────

func main() {
	port := flag.Int("port", 4003, "WebSocket relay listen port")
	flag.Parse()

	startTime = time.Now()
	relay := NewRelay()

	// WebSocket endpoint
	http.Handle("/ws", handleWebSocket(relay))

	// Health endpoint
	http.HandleFunc("/health", healthHandler(relay))

	// Start HTTP server (handles both WS upgrade and health)
	go func() {
		addr := fmt.Sprintf(":%d", *port)
		log.Printf("WebSocket relay listening on %s/ws", addr)
		if err := http.ListenAndServe(addr, nil); err != nil {
			log.Fatalf("Server error: %v", err)
		}
	}()

	// Wait for shutdown
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	<-sigCh
	log.Println("Shutting down...")
}
