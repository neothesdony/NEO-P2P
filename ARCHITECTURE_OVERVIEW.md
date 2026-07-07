# NEO-P2P Architecture Overview


## Architecture Overview

Render with `d2 ARCHITECTURE_DIAGRAMS.d2 output.svg`.

### Layers

**Mobile App** → UI / ViewModels / Repositories / Use Cases / Data Models

**P2P Networks** → Nostr (messaging), libp2p (direct P2P), Lightning Network (escrow)

**Local Storage** → SQLCipher DB, KeyStore/Keychain

### Data Flow

| Direction | From | To | Description |
|-----------|------|----|-------------|
| Observes | UI | ViewModels | State collection |
| Calls | ViewModels | Repositories | Business logic |
| Publishes/Subscribes | Repositories | Nostr | Offer events |
| Streams | Repositories | libp2p | Direct P2P data |
| Creates/Signs | Repositories | Lightning Network | Escrow transactions |
| Persists | Models | SQLCipher DB | Local storage |
