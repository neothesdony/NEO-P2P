import SwiftUI

struct OfferDetailScreen: View {
    let offerId: String
    @StateObject private var viewModel = OfferDetailViewModel()
    @Environment(\.presentationMode) var presentationMode
    
    var body: some View {
        Group {
            switch viewModel.uiState {
            case .loading:
                LoadingView()
            case .error(let message):
                ErrorView(message: message) {
                    viewModel.loadOffer(offerId: offerId)
                }
            case .success(let detailData):
                OfferDetailContentView(
                    offer: detailData.offer,
                    peer: detailData.peer,
                    reputation: detailData.reputation,
                    onChatClick: {
                        // Navigate to chat screen with offer and peer IDs
                    }
                )
            }
        }
        .navigationTitle("Offer Details")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button(action: {
                    presentationMode.wrappedValue.dismiss()
                }) {
                    Image(systemName: "chevron.left")
                }
            }
        }
        .onAppear {
            viewModel.loadOffer(offerId: offerId)
        }
    }
}

struct OfferDetailContentView: View {
    let offer: Offer
    let peer: Peer?
    let reputation: ReputationScore?
    let onChatClick: () -> Void
    
    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                // Offer header
                HStack(spacing: 12) {
                    Image(systemName: offer.type == .buy ? "arrow.down.circle.fill" : "arrow.up.circle.fill")
                        .font(.system(size: 24))
                        .foregroundColor(offer.type == .buy ? .blue : .green)
                    
                    VStack(alignment: .leading, spacing: 4) {
                        Text(offer.type == .buy ? "BUYING BTC" : "SELLING BTC")
                            .font(.title2)
                            .fontWeight(.bold)
                            .foregroundColor(offer.type == .buy ? .blue : .green)
                        
                        Text("Offer #\(offer.offerId.prefix(6))...")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    
                    Spacer()
                }
                .padding()
                .background(Color(.systemBackground))
                .cornerRadius(16)
                
                // Offer details card
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 12) {
                        DetailRow(label: "Amount", value: "\(Double(offer.cryptoAmountSats) / 100_000_000) BTC")
                        DetailRow(label: "Price", value: "Rp \(String(format: "%,.0f", offer.pricePerUnit))/BTC")
                        DetailRow(label: "Total Fiat", value: "Rp \(String(format: "%,.0f", offer.fiatAmount))")
                        DetailRow(label: "Fee (1%)", value: "\(offer.feeSats) sats")
                        DetailRow(label: "Total Deposit", value: "\(offer.totalDepositSats) sats")
                    }
                    .padding()
                    .background(Color(.systemBackground))
                    .cornerRadius(12)
                }
                
                // Payment methods
                VStack(alignment: .leading, spacing: 12) {
                    Text("Payment Methods")
                        .font(.title2)
                        .fontWeight(.semibold)
                    
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(offer.fiatMethods, id: \.self) { method in
                            HStack {
                                Image(systemName: "banknote")
                                    .font(.system(size: 16))
                                    .frame(width: 24, height: 24)
                                    .background(Color(.systemGray5))
                                    .clipShape(Circle())
                                
                                Text(method.uppercased())
                                    .font(.body)
                                    .fontWeight(.medium)
                                
                                Spacer()
                            }
                        }
                    }
                    .padding()
                    .background(Color(.systemBackground))
                    .cornerRadius(12)
                }
                
                // Trader info
                VStack(alignment: .leading, spacing: 12) {
                    Text("Trader")
                        .font(.title2)
                        .fontWeight(.semibold)
                    
                    HStack(alignment: .top, spacing: 12) {
                        // Avatar
                        ZStack {
                            Circle()
                                .fill(Color.green.opacity(0.2))
                                .frame(width: 50, height: 50)
                            
                            Text(peer?.nickname.prefix(1).uppercased() ?? "?")
                                .font(.title3)
                                .fontWeight(.bold)
                                .foregroundColor(.green)
                        }
                        
                        VStack(alignment: .leading, spacing: 4) {
                            Text(peer?.nickname ?? "Anonymous")
                                .font(.title3)
                                .fontWeight(.semibold)
                            
                            if let reputation = reputation {
                                HStack(spacing: 4) {
                                    Text("\(Int(reputation.score * 100))%")
                                        .font(.caption)
                                        .fontWeight(.bold)
                                        .foregroundColor(reputationScoreColor(reputation.score))
                                    
                                    Text("\(reputation.totalTrades) trades")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                            } else {
                                Text("No reputation yet")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                        
                        Spacer()
                    }
                    .padding()
                    .background(Color(.systemBackground))
                    .cornerRadius(12)
                }
                
                Spacer(minLength: 0)
                
                // Action button
                Button(action: onChatClick) {
                    Text("Start Trade")
                        .fontWeight(.semibold)
                        .frame(maxWidth: .infinity)
                        .frame(height: 56)
                        .background(Color.green)
                        .foregroundColor(.white)
                        .cornerRadius(14)
                }
                .padding(.horizontal)
            }
            .padding()
        }
        .background(Color(.systemGroupedBackground))
    }
    
    private func reputationScoreColor(_ score: Float) -> Color {
        if score >= 0.9 {
            return .green
        } else if score >= 0.7 {
            return .blue
        } else {
            return .red
        }
    }
}

struct DetailRow: View {
    let label: String
    let value: String
    
    var body: some View {
        HStack {
            Text(label)
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .fontWeight(.medium)
        }
        .padding(.vertical, 4)
    }
}

struct LoadingView: View {
    var body: some View {
        VStack {
            ProgressView()
                .scaleEffect(1.5)
            Text("Loading offer details...")
                .font(.caption)
                .foregroundColor(.secondary)
                .padding(.top, 8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct ErrorView: View {
    let message: String
    let retryAction: () -> Void
    
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 48))
                .foregroundColor(.orange)
            
            Text(message)
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
                .padding(.horizontal, 32)
            
            Button(action: retryAction) {
                Text("Retry")
                    .fontWeight(.semibold)
                    .frame(width: 100, height: 40)
                    .background(Color.green)
                    .foregroundColor(.white)
                    .cornerRadius(8)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - ViewModel

class OfferDetailViewModel: ObservableObject {
    enum UiState {
        case loading
        case error(String)
        case success(offer: Offer, peer: Peer?, reputation: ReputationScore?)
    }
    
    @Published var uiState: UiState = .loading
    
    init() {
        // Will load when loadOffer is called
    }
    
    func loadOffer(offerId: String) {
        uiState = .loading
        
        // Simulate loading from shared module or repository
        // In a real app, this would come from a use case like GetOfferDetailUseCase
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
            let offer = Offer(
                id: UUID(),
                offerId: offerId.isEmpty ? "offer_1" : offerId,
                creatorPeerId: "peer_creator",
                type: .sell, // or .buy
                cryptoAmountSats: 500_000, // 0.005 BTC
                fiatAmount: 7_500_000, // 75,000 IDR
                pricePerUnit: 1_500_000_000.0, // 1,500,000 IDR per BTC
                fiatMethods: ["bca", "gopay", "dana"],
                status: .open,
                feeSats: 5_000 // 1% fee
            )
            
            let peer = Peer(
                id: UUID(),
                peerId: "peer_creator",
                nickname: "Trader_Budi",
                nostrPubkey: "npub1...",
                lnNodeId: "02abc...",
                reputationScore: 0.85,
                totalTrades: 42
            )
            
            let reputation = ReputationScore(
                score: 0.85,
                totalTrades: 42
            )
            
            self?.uiState = .success(offer: offer, peer: peer, reputation: reputation)
        }
    }
}

// MARK: - Data Models

struct Offer: Identifiable, Equatable {
    let id: UUID
    let offerId: String
    let creatorPeerId: String
    let type: OfferType // buy or sell
    let cryptoAmountSats: Int
    let fiatAmount: Int // in IDR
    let pricePerUnit: Double // IDR per satoshi
    let fiatMethods: [String]
    let status: OfferStatus
    let feeSats: Int
    let totalDepositSats: Int { cryptoAmountSats + feeSats }
    
    enum OfferType: String, Equatable {
        case buy
        case sell
    }
    
    enum OfferStatus: String, Equatable {
        case open
        case pending
        case completed
        case cancelled
        case disputed
    }
}

struct Peer: Identifiable, Equatable {
    let id: UUID
    let peerId: String
    let nickname: String
    let nostrPubkey: String
    let lnNodeId: String
    let reputationScore: Float // 0.0 to 1.0
    let totalTrades: Int
}

struct ReputationScore: Equatable {
    let score: Float // 0.0 to 1.0
    let totalTrades: Int
}

#Preview {
    OfferDetailScreen(offerId: "offer_123")
}