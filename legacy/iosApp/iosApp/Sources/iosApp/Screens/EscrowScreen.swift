import SwiftUI

struct EscrowScreen: View {
    let escrowId: String
    @StateObject private var viewModel = EscrowViewModel()
    @Environment(\.presentationMode) var presentationMode
    
    var body: some View {
        Group {
            switch viewModel.uiState {
            case .loading:
                LoadingView()
            case .error(let message):
                ErrorView(message: message) {
                    viewModel.refresh()
                }
            case .success(let escrowData):
                EscrowContentView(
                    escrow: escrowData.escrow,
                    onConfirmPayment: { viewModel.confirmPayment() },
                    onReleaseFunds: { viewModel.releaseFunds() },
                    onDispute: { viewModel.disputeEscrow() }
                )
            }
        }
        .navigationTitle("Escrow Details")
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
    }
}

struct EscrowContentView: View {
    let escrow: Escrow
    let onConfirmPayment: () -> Void
    let onReleaseFunds: () -> Void
    let onDispute: () -> Void
    
    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                // Escrow header
                HStack(spacing: 12) {
                    Image(systemName: statusIcon)
                        .font(.system(size: 24))
                        .foregroundColor(statusColor)
                    
                    VStack(alignment: .leading, spacing: 4) {
                        Text(statusText)
                            .font(.title3)
                            .fontWeight(.semibold)
                        
                        Text("Escrow #\(escrow.escrowId.prefix(6))...")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    
                    Spacer()
                }
                .padding()
                .background(Color(.systemBackground))
                .cornerRadius(16)
                
                Divider()
                
                // Trade details
                VStack(alignment: .leading, spacing: 16) {
                    Text("Trade Details")
                        .font(.title3)
                        .fontWeight(.semibold)
                    
                    DetailRow(label: "Amount", value: "\(Double(escrow.tradeAmountSats) / 100_000_000) BTC")
                    DetailRow(label: "Price per BTC", value: formattedPricePerBTC)
                    DetailRow(label: "NEO-P2P Fee (1%)", value: "\(escrow.feeAmountSats) sats")
                    DetailRow(label: "Total Required", value: "\(escrow.depositAmountSats) sats")
                }
                .padding()
                .background(Color(.systemBackground))
                .cornerRadius(16)
                
                Divider()
                
                // Payment instructions
                VStack(alignment: .leading, spacing: 12) {
                    Text("Payment Instructions")
                        .font(.title3)
                        .fontWeight(.semibold)
                    
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Bank Transfer:")
                            .font(.headline)
                        Text("Bank: BCA")
                        Text("A/N: *** Sari")
                        Text("No: 1234567890")
                        Text("Amount: Rp 1,500,000")
                        Text("Note: \"btc123\"")
                    }
                    .font(.body)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                    .background(Color(.systemGray6))
                    .cornerRadius(12)
                }
                .padding()
                .background(Color(.systemBackground))
                .cornerRadius(16)
                
                Divider()
                
                // Action buttons
                VStack(spacing: 12) {
                    Button(action: onConfirmPayment) {
                        Text(statusButtonTitle)
                            .fontWeight(.semibold)
                            .frame(maxWidth: .infinity)
                            .frame(height: 48)
                            .background(statusButtonColor)
                            .foregroundColor(.white)
                            .cornerRadius(12)
                    }
                    
                    if escrow.status == .funded {
                        Button(action: onReleaseFunds) {
                            Text("Release Funds")
                                .fontWeight(.semibold)
                                .frame(maxWidth: .infinity)
                                .frame(height: 48)
                                .background(Color.green)
                                .foregroundColor(.white)
                                .cornerRadius(12)
                        }
                    }
                    
                    if escrow.status == .funded {
                        Button(action: onDispute) {
                            Text("Open Dispute")
                                .fontWeight(.semibold)
                                .frame(maxWidth: .infinity)
                                .frame(height: 48)
                                .border(Color.red, width: 1)
                                .foregroundColor(.red)
                                .cornerRadius(12)
                        }
                    }
                }
                .padding()
                
                Spacer(minLength: 0)
                
                // Fee transparency
                VStack(alignment: .leading, spacing: 8) {
                    Text("Fee Transparency")
                        .font(.headline)
                    
                    Text("1% of every trade goes to:")
                        .font(.subheadline)
                    
                    Text(escrow.feeAddress)
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(8)
                        .background(Color(.systemBackground))
                        .cornerRadius(6)
                    
                    Text("(This address is hardcoded in the open-source app)")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
                .padding()
                .background(Color(.systemBackground))
                .cornerRadius(12)
            }
            .padding()
        }
        .background(Color(.systemGroupedBackground))
    }
    
    private var statusIcon: String {
        switch escrow.status {
        case .funding: return "lock.open.fill"
        case .funded: return "lock.fill"
        case .released: return "checkmark.shield.fill"
        case .disputed: return "exclamationmark.triangle.fill"
        case .refunded: return "arrow.triangle.2.circlepath"
        }
    }
    
    private var statusColor: Color {
        switch escrow.status {
        case .funding: return .orange
        case .funded: return .blue
        case .released: return .green
        case .disputed: return .red
        case .refunded: return .blue
        }
    }
    
    private var statusText: String {
        switch escrow.status {
        case .funding: return "Waiting for Deposit"
        case .funded: return "Deposit Confirmed"
        case .released: return "Completed"
        case .disputed: return "In Dispute"
        case .refunded: return "Refunded"
        }
    }
    
    private var statusButtonTitle: String {
        switch escrow.status {
        case .funding: return "I've Made Payment"
        case .funded: return "Release Funds"
        default: return ""
        }
    }
    
    private var statusButtonColor: Color {
        switch escrow.status {
        case .funding: return .green
        case .funded: return .green
        default: return .gray
        }
    }
    
    private var formattedPricePerBTC: String {
        guard escrow.tradeAmountSats > 0 else { return "Rp 0" }
        let pricePerSat = Double(escrow.depositAmountSats) / Double(escrow.tradeAmountSats)
        let pricePerBTC = pricePerSat * 100_000_000
        return String(format: "Rp %.0f", pricePerBTC)
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
            Text("Loading escrow details...")
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

class EscrowViewModel: ObservableObject {
    enum UiState {
        case loading
        case error(String)
        case success(escrow: Escrow)
    }
    
    @Published var uiState: UiState = .loading
    
    init() {
        loadEscrow()
    }
    
    func loadEscrow() {
        uiState = .loading
        
        // Simulate loading from shared module or repository
        // In a real app, this would come from a use case like GetEscrowUseCase
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
            let escrow = Escrow(
                escrowId: "escrow_123456",
                offerId: "offer_123",
                type: .lightning,
                depositAmountSats: 1_010_000, // 1.01 BTC (1% fee)
                tradeAmountSats: 1_000_000, // 1.00 BTC
                feeAmountSats: 10_000, // 0.01 BTC fee
                feeAddress: "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                buyerPeerId: "buyer_peer_123",
                sellerPeerId: "seller_peer_456",
                status: .funding
            )
            
            self?.uiState = .success(escrow: escrow)
        }
    }
    
    func refresh() {
        loadEscrow()
    }
    
    func confirmPayment() {
        // Simulate confirming payment and moving to funded state
        switch uiState {
        case .success(var escrow):
            escrow.status = .funded
            uiState = .success(escrow: escrow)
        default:
            break
        }
    }
    
    func releaseFunds() {
        // Simulate releasing funds
        switch uiState {
        case .success(var escrow):
            escrow.status = .released
            uiState = .success(escrow: escrow)
        default:
            break
        }
    }
    
    func disputeEscrow() {
        // Simulate opening a dispute
        switch uiState {
        case .success(var escrow):
            escrow.status = .disputed
            uiState = .success(escrow: escrow)
        default:
            break
        }
    }
}

// MARK: - Data Models

struct Escrow: Identifiable, Equatable {
    let id = UUID()
    let escrowId: String
    let offerId: String
    let type: EscrowType
    let depositAmountSats: Int
    let tradeAmountSats: Int
    let feeAmountSats: Int
    let feeAddress: String
    let buyerPeerId: String
    let sellerPeerId: String
    var status: EscrowStatus
    
    enum EscrowType: String, Equatable {
        case lightning
    }
    
    enum EscrowStatus: String, Equatable {
        case funding
        case funded
        case released
        case disputed
        case refunded
    }
}

#Preview {
    EscrowScreen(escrowId: "escrow_123456")
}