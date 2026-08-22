#!/usr/bin/env python3
"""
Re-sign the NEO-P2P fee wallet address with the owner's Ed25519 private key.

Usage (from repo root):
    python3 android/scripts/re-sign-fee-wallet.py "bc1qnewaddress..."

This reads the private key from android/fee-wallet-secret.key (gitignored),
signs the given address, and prints the PUBLIC_KEY_HEX and SIGNATURE_HEX to
paste into android/app/src/main/java/com/neop2p/NeoP2PConfig.kt.

Only the owner holds the private key, so only the owner can change the fee
address in a way the app will accept.
"""
import sys
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization

SECRET_FILE = Path(__file__).resolve().parent.parent / "fee-wallet-secret.key"

def main() -> None:
    if len(sys.argv) != 2:
        print("Usage: re-sign-fee-wallet.py <NEW_FEE_ADDRESS>")
        sys.exit(1)
    new_addr = sys.argv[1].strip()

    # Load the private key from the gitignored secret file
    if not SECRET_FILE.exists():
        print(f"ERROR: {SECRET_FILE} not found. It is gitignored and must be "
              "present on the owner's machine.")
        sys.exit(1)

    priv_hex = None
    for line in SECRET_FILE.read_text().splitlines():
        line = line.strip()
        if line.startswith("FEE_WALLET_SIGNER_PRIVATE_KEY="):
            priv_hex = line.split("=", 1)[1].strip()
            break
    if not priv_hex:
        print("ERROR: FEE_WALLET_SIGNER_PRIVATE_KEY not found in secret file.")
        sys.exit(1)

    priv = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(priv_hex))
    pub = priv.public_key()
    pub_hex = pub.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw).hex()

    sig = priv.sign(new_addr.encode("utf-8"))
    sig_hex = sig.hex()

    # Verify before printing
    pub.verify(sig, new_addr.encode("utf-8"))

    print("=== NEW FEE WALLET ADDRESS ===")
    print(new_addr)
    print()
    print("=== Paste these into NeoP2PConfig.kt ===")
    print(f"FEE_WALLET_ADDRESS = \"{new_addr}\"")
    print(f"FEE_WALLET_SIGNER_PUBLIC_KEY = \"{pub_hex}\"")
    print(f"FEE_WALLET_SIGNATURE_HEX = \"{sig_hex}\"")
    print()
    print("(Public key stays the same unless you rotate the keypair.)")

if __name__ == "__main__":
    main()
