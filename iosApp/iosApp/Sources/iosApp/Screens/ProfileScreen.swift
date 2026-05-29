import SwiftUI

struct ProfileScreen: View {
    @StateObject private var viewModel = ProfileViewModel()
    @State private var showingEditNickname = false
    @State private var showingAttestations = false
    @State private var newNickname = ""
    
    var body: some View {
        Group {
            switch viewModel.uiState {
            case .loading:
                LoadingView()
            case .error(let message):
                ErrorView(message: message) {
                    viewModel.refresh()
                }
            case .success(let profileData):
                ProfileContentView(
                    identity: profileData.identity,
                    reputation: profileData.reputation,
                    onEditNickname: { showingEditNickname = true },
                    onViewAttestations: { showingAttestations = true }
                )
            }
        }
        .navigationTitle("Profile")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button(action: {
                    // Back action would be handled by navigation stack
                }) {
                    Image(systemName: "chevron.left")
                }
            }
        }
        .sheet(isPresented: $showingEditNickname) {
            EditNicknameView(nickname: $newNickname, isPresented: $showingEditNickname) {
                viewModel.updateNickname(newNickname)
            }
        }
        .sheet(isPresented: $showingAttestations) {
            AttestationsView(attestations: viewModel.attestations)
        }
    }
}

struct ProfileContentView: View {
    let identity: Identity
    let reputation: ReputationProfile
    let onEditNickname: () -> Void
    let onViewAttestations: () -> Void
    
    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                // Avatar section
                VStack(spacing: 12) {
                    // Large avatar
                    ZStack {
                        Circle()
                            .fill(Color.green)
                            .frame(width: 80, height: 80)
                        
                        Text(avatarInitials)
                            .font(.system(size: 36, weight: .bold))
                            .foregroundColor(.white)
                    }
                    
                    Text(identity.nickname.isEmpty ? "Anonymous" : identity.nickname)
                        .font(.title2)
                        .fontWeight(.bold)
                    
                    Text("#\(identity.peerId.prefix(8))")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                
                // Reputation stats
                VStack(alignment: .leading, spacing: 16) {
                    Text("Reputation")
                        .font(.title3)
                        .fontWeight(.semibold)
                    
                    HStack(spacing: 20) {
                        StatCardView(label: "Score", value: "\(Int(reputation.score * 100))%")
                        StatCardView(label: "Trades", value: "\(reputation.totalTrades)")
                        StatCardView(label: "Completed", value: "\(reputation.completedTrades)")
                        StatCardView(label: "Disputes", value: "\(reputation.disputedTrades)")
                    }
                }
                
                // Cryptographic identity
                VStack(alignment: .leading, spacing: 16) {
                    Text("Identity")
                        .font(.title3)
                        .fontWeight(.semibold)
                    
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("Nostr:")
                                .font(.caption)
                                .foregroundColor(.secondary)
                            Spacer()
                            Text(identity.nostrPubkeyHex.prefix(20) + "...")
                                .font(.caption.monospaced())
                                .textSelection(.enabled)
                                .contextMenu {
                                    Button("Copy") {
                                        UIPasteboard.general.string = String(identity.nostrPubkeyHex.prefix(20) + "...")
                                    }
                                }
                        }
                        
                        HStack {
                            Text("Lightning:")
                                .font(.caption)
                                .foregroundColor(.secondary)
                            Spacer()
                            Text(identity.lnNodeId.prefix(20) + "...")
                                .font(.caption.monospaced())
                                .textSelection(.enabled)
                                .contextMenu {
                                    Button("Copy") {
                                        UIPasteboard.general.string = String(identity.lnNodeId.prefix(20) + "...")
                                    }
                                }
                        }
                    }
                    .padding()
                    .background(Color(.systemBackground))
                    .cornerRadius(12)
                }
                
                // Action buttons
                VStack(spacing: 12) {
                    Button(action: onEditNickname) {
                        HStack {
                            Image(systemName: "pencil")
                            Text("Edit Nickname")
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .background(Color.green)
                        .foregroundColor(.white)
                        .cornerRadius(12)
                    }
                    
                    Button(action: onViewAttestations) {
                        HStack {
                            Image(systemName: "text.badge.checkmark")
                            Text("View Attestations")
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .border(Color.green, width: 1)
                        .foregroundColor(.green)
                        .cornerRadius(12)
                    }
                }
            }
            .padding()
        }
        .background(Color(.systemGroupedBackground))
    }
    
    private var avatarInitials: String {
        guard let firstChar = identity.nickname.first else { return "?" }
        return String(firstChar).uppercased()
    }
}

struct StatCardView: View {
    let label: String
    let value: String
    
    var body: some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.title3)
                .fontWeight(.bold)
            Text(label)
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .frame(minWidth: 0, maxWidth: .infinity)
        .padding()
        .background(Color(.systemBackground))
        .cornerRadius(8)
    }
}

struct LoadingView: View {
    var body: some View {
        VStack {
            ProgressView()
                .scaleEffect(1.5)
            Text("Loading profile...")
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

struct EditNicknameView: View {
    @Binding var nickname: String
    @Binding var isPresented: Bool
    let onSave: () -> Void
    @State private var showingError = false
    
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Nickname", text: $nickname)
                        .autocapitalization(.none)
                }
                
                Section(header: Text("Nickname Guidelines")) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("• 3-20 characters")
                        Text("• Letters, numbers, and underscores only")
                        Text("• No personal information")
                    }
                    .font(.caption)
                    .foregroundColor(.secondary)
                }
            }
            .navigationTitle("Edit Nickname")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        isPresented = false
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave()
                        isPresented = false
                    }
                    .disabled(!isValidNickname)
                }
            }
            .alert("Invalid Nickname", isPresented: $showingError) {
                Button("OK", role: .cancel) { }
            } message: {
                Text("Please enter a valid nickname (3-20 characters, letters/numbers/underscores only)")
            }
            .onChange(of: nickname) { _ in
                showingError = !isValidNickname && !nickname.isEmpty
            }
        }
    }
    
    private var isValidNickname: Bool {
        let regex = try? NSRegularExpression(pattern: "^[a-zA-Z0-9_]{3,20}$")
        return regex?.firstMatch(in: nickname, range: NSRange(nickname.startIndex..., in: nickname)) != nil
    }
}

struct AttestationsView: View {
    let attestations: [Attestation]
    
    var body: some View {
        List {
            if attestations.isEmpty {
                Text("No attestations yet")
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding()
            } else {
                ForEach(attestations) { attestation in
                    AttestationRow(attestation: attestation)
                }
            }
        }
        .navigationTitle("Attestations")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct AttestationRow: View {
    let attestation: Attestation
    
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: attestation.isValid ? "checkmark.shield.fill" : "xmark.shield.fill")
                    .foregroundColor(attestation.isValid ? .green : .red)
                
                VStack(alignment: .leading) {
                    Text("From: \(attestation.attesterPeerId.prefix(8))...")
                        .font(.caption)
                    Text("About: \(attestation.aboutPeerId.prefix(8))...")
                        .font(.caption)
                }
                Spacer()
                
                Text(attestation.timestamp, style: .date)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
            
            if !attestation.comment.isEmpty {
                Text(attestation.comment)
                    .font(.body)
                    .padding(8)
                    .background(Color(.systemGray6))
                    .cornerRadius(6)
            }
        }
        .padding()
        .background(Color(.systemBackground))
        .cornerRadius(12)
    }
}

// MARK: - ViewModel

class ProfileViewModel: ObservableObject {
    enum UiState {
        case loading
        case error(String)
        case success(identity: Identity, reputation: ReputationProfile)
    }
    
    @Published var uiState: UiState = .loading
    @Published var attestations: [Attestation] = []
    
    init() {
        loadProfile()
    }
    
    func loadProfile() {
        uiState = .loading
        
        // Simulate loading from shared module or repository
        // In a real app, this would come from a use case like GetProfileUseCase
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
            let identity = Identity(
                nickname: "trader_john",
                peerId: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                nostrPubkeyHex: "a1b2c3d4e5f67890123456789abcdef0123456789abcdef0123456789abcdef0",
                lnNodeId: "02a1b2c3d4e5f67890123456789abcdef0123456789abcdef0123456789abcdef0"
            )
            
            let reputation = ReputationProfile(
                peerId: identity.peerId,
                score: 0.95,
                totalTrades: 42,
                completedTrades: 40,
                disputedTrades: 2
            )
            
            let sampleAttestations = [
                Attestation(
                    attesterPeerId: "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
                    aboutPeerId: identity.peerId,
                    isValid: true,
                    comment: "Great trader, fast payment and clear communication",
                    timestamp: Date().addingTimeInterval(-86400 * 5)
                ),
                Attestation(
                    attesterPeerId: "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
                    aboutPeerId: identity.peerId,
                    isValid: true,
                    comment: "Reliable counterparty, always follows through",
                    timestamp: Date().addingTimeInterval(-86400 * 3)
                )
            ]
            
            self?.uiState = .success(identity: identity, reputation: reputation)
            self?.attestations = sampleAttestations
        }
    }
    
    func refresh() {
        loadProfile()
    }
    
    func updateNickname(_ nickname: String) {
        // In a real app, this would update the identity through a use case
        // For now, we'll just simulate success
        switch uiState {
        case .success(var identity, let reputation):
            identity.nickname = nickname
            uiState = .success(identity: identity, reputation: reputation)
        default:
            break
        }
    }
}

// MARK: - Data Models

struct Identity: Equatable {
    let nickname: String
    let peerId: String
    let nostrPubkeyHex: String
    let lnNodeId: String
}

struct ReputationProfile: Equatable {
    let peerId: String
    let score: Float // 0.0 to 1.0
    let totalTrades: Int
    let completedTrades: Int
    let disputedTrades: Int
}

struct Attestation: Identifiable, Equatable {
    let id = UUID()
    let attesterPeerId: String
    let aboutPeerId: String
    let isValid: Bool
    let comment: String
    let timestamp: Date
}

#Preview {
    ProfileScreen()
}