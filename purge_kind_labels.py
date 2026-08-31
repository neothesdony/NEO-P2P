#!/usr/bin/env python3
"""Purge Nostr-era kind:33NNN comment labels from NEO-P2P Phase 4 (LXMF-only).

Mapping (verified against code — all are COMMENT-ONLY references; zero string
literals contain these labels, so wire data is untouched):
  kind:33333 -> neop2p/offers RNS announce (offer digest)
  kind:33335 -> local-only attestation (Nostr gossip removed)
  kind:33336 -> LXMF offer_status message
  kind:33337 -> LXMF escrow_status message
  kind:33386 -> LXMF dispute message
  kind:33387 -> LXMF evidence message
  kind:33388 -> LXMF resolution message
"""
import re, os, sys

SRC = "/home/thesdony/neop2p-reticulum/android/app/src/main/java"

KIND_MAP = [
    ("kind:33337", "LXMF escrow_status"),
    ("kind:33336", "LXMF offer_status"),
    ("kind:33388", "LXMF resolution message"),
    ("kind:33387", "LXMF evidence message"),
    ("kind:33386", "LXMF dispute message"),
    ("kind:33335", "local attestation"),
    ("kind:33333", "neop2p/offers announce"),
    # Generic fallback for any kind:3xxxx / kind:5 not mapped above
    (r"kind:3\d{4,5}", "LXMF message"),
    (r"kind:5\b", "offer deletion (local tombstone)"),
]

def rewrite(text: str) -> str:
    for pat, repl in KIND_MAP:
        text = re.sub(pat, repl, text)
    return text

changed = []
total_edits = 0
for root, _dirs, files in os.walk(SRC):
    for fname in files:
        if not fname.endswith(".kt"):
            continue
        path = os.path.join(root, fname)
        with open(path, "r", errors="replace") as f:
            orig = f.read()
        new = rewrite(orig)
        if new != orig:
            edits = sum(orig.count(p) for p, _ in KIND_MAP)  # rough; count real diffs below
            with open(path, "w") as f:
                f.write(new)
            changed.append((path, edits))
            total_edits += 1

print(f"Files rewritten: {len(changed)}")
for p, _e in sorted(changed):
    print(f"  {p}")
# Sanity: no kind: labels should remain
leftover = 0
for root, _dirs, files in os.walk(SRC):
    for fname in files:
        if fname.endswith(".kt"):
            with open(os.path.join(root, fname), errors="replace") as f:
                c = f.read()
            leftover += len(re.findall(r"kind:[0-9]", c))
print(f"Remaining kind: labels: {leftover}")
