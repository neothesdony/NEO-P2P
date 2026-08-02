import SwiftUI

struct CreateOfferScreen: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var viewModel = CreateOfferViewModel()
    @State private var showingFeeInfo = false
    
    var body: some View {
        NavigationStack {
            Form {
                Section(header: Text("Offer Details")) {
                    Picker("Offer Type", selection: $viewModel.offerType) {
                        Text("Buy Bitcoin").tag(OfferType.buy)
                        Text("Sell Bitcoin").tag(OfferType.sell)
                    }
                    .pickerStyle(.segmented)
                    
                    Stepper(value: $viewModel.amount, in: 1000...1000000, step: 1000) {
                        HStack {
                            Text("Amount:")
                            Spacer()
                            Text("\(viewModel.amount.formatted()) SATS")
                                .fontWeight(.medium)
                        }
                    }
                }
                
                Section(header: Text("Payment Info")) {
                    TextField("Your Lightning Invoice (optional)", text: $viewModel.paymentDetails)
                        .keyboardType(.alphabet)
                    
                    Toggle(isOn: $viewModel.requiresIdentityVerification) {
                        Text("Require ID verification")
                    }
                }
                
                Section {
                    Button(action: {
                        viewModel.createOffer { success in
                            if success {
                                dismiss()
                            }
                        }
                    }) {
                        HStack {
                            Spacer()
                            Text("Create Offer")
                                .fontWeight(.semibold)
                            Spacer()
                        }
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(viewModel.isFormValid ? Color.green : Color.gray)
                        .foregroundColor(.white)
                        .cornerRadius(12)
                    }
                    .disabled(!viewModel.isFormValid)
                }
            }
            .navigationTitle("Create Offer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: {
                        showingFeeInfo = true
                    }) {
                        Image(systemName: "info.circle")
                    }
                }
            }
            .alert("Fee Information", isPresented: $showingFeeInfo) {
                Button("Got it", role: .cancel) { }
            } message: {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Fee Structure:")
                        .font(.headline)
                    Text("• 1% commission on all trades")
                    Text("• Fee goes to liquidity providers")
                    Text("• Minimum fee: 1 SAT")
                    Divider()
                    Text("Example: 10,000 SAT trade")
                        .font(.subheadline)
                    Text("→ Fee: 100 SAT")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
            }
            .overlay {
                if viewModel.isLoading {
                    ProgressView()
                }
            }
        }
    }
}

class CreateOfferViewModel: ObservableObject {
    @Published var offerType: OfferType = .buy
    @Published var amount: Int = 50000
    @Published var paymentDetails: String = ""
    @Published var requiresIdentityVerification: Bool = false
    @Published var isLoading: Bool = false
    @Published var errorMessage: String? = nil
    
    var isFormValid: Bool {
        amount >= 1000 && !paymentDetails.isEmpty
    }
    
    func createOffer(completion: @escaping (Bool) -> Void) {
        guard isFormValid else {
            completion(false)
            return
        }
        
        isLoading = true
        errorMessage = nil
        
        // Simulate network call to create offer
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            self?.isLoading = false
            
            // In a real app, this would call a use case like CreateOfferUseCase
            // For demo, we'll simulate success
            completion(true)
        }
    }
}

enum OfferType: String, CaseIterable, Identifiable {
    case buy, sell
    
    var id: String { self.rawValue }
    
    var displayName: String {
        switch self {
        case .buy: return "Buy Bitcoin"
        case .sell: return "Sell Bitcoin"
        }
    }
}

#Preview {
    CreateOfferScreen()
}