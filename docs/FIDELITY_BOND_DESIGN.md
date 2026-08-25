# Fidelity Bond Design (P2 — deferred)

**Status: design only, not implemented.** Reference: RoboSats fidelity bonds + Mostro anti-abuse bonds.

## Problem

Anyone can post a sell offer with zero cost. Ghost offers (post → never fund the escrow) waste buyers' time and poison the order book. Binance solves this with KYC + account bans; a zero-backend app needs an economic deterrent.

## Design

**Maker bond (RoboSats model):** the seller locks a small on-chain bond (e.g. 1–2% of the offer amount, capped) into a 2-of-3 bond escrow when posting an offer. The bond is:

- **Returned** when the trade completes honestly (bond output included in the payout tx, back to the seller).
- **Slashed** when the seller's escrow auto-cancels (30-min unfunded timeout) or the seller loses a dispute — the slashed bond goes to the fee wallet (or the buyer as compensation).

**Mechanics that fit NEO-P2P's existing architecture:**

- Reuse the existing 2-of-3 P2SH machinery: a bond is just a tiny escrow with `tradeAmountSats = 0` and a `bond` flag.
- The bond deposit happens at offer creation (seller funds it like the escrow, via the same `WalletService.send` + on-chain verify path).
- The bond is released on `RELEASED` (add a bond output to the payout tx) or refunded on `REFUNDED`/`CANCELLED`-with-deposit.
- `expireStaleEscrows()` already handles the timeout sweep — extend it to slash bonds on auto-cancel.

**Why deferred:**

1. Requires a new escrow variant (bond escrow) + payout tx changes (extra output) — touches the most safety-critical code in the app.
2. On-chain cost: every offer costs a deposit + refund tx (~2 × 220 vB fees). On testnet4 that's free, but on mainnet it prices out small offers.
3. Value only materializes at scale — with a handful of users, ghost offers are a minor annoyance, not a market failure.

**Alternative (cheaper, no on-chain):** reputation-gated posting — require `totalTrades >= 1` and `score >= 0.5` before a peer can post offers (Mostro-style). Zero tx cost, uses the reputation system this batch just wired up. Revisit the on-chain bond only if ghost offers become a real problem.

## Open questions

- Bond amount formula (fixed vs % of offer)?
- Who holds the bond's 3rd key — the arbitrator (same as escrow)?
- Slash destination: fee wallet vs buyer compensation?
