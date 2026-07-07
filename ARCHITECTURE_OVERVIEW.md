# NEO-P2P Architecture Overview


```d2
direction: down

App: Mobile App {
  UI: User Interface
  VM: ViewModels
  REPO: Repositories
  UC: Use Cases
  MODEL: Data Models
}

P2P: P2P Networks {
  NOSTR: Nostr (Messaging & Social Layer)
  LIBP2P: libp2p (Direct P2P Communication)
  LN: Lightning Network (Trustless Escrow)
}

Storage: Local Storage {
  DB: Local Database
  SECURE: Secure Storage
}

UI --> VM: Observes
VM --> REPO: Calls
REPO --> UC: Executes
UC --> MODEL: Manages
MODEL --> DB: Persists to
MODEL --> SECURE: Secrets in

REPO --> NOSTR: Publishes/Subscribes
REPO --> LIBP2P: Streams/Files
REPO --> LN: Creates/Signs

NOSTR <-- REPO: Events
LIBP2P <-- REPO: Streams/Data
LN <-- REPO: Transactions
```
