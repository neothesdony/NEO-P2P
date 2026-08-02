import Foundation
import Combine

class HomeViewModel: ObservableObject {
    @Published var offers: [Offer] = []
    @Published var isLoading: Bool = false
    @Published var errorMessage: String? = nil
    
    private var cancellables = Set<AnyCancellable>()
    
    init() {
        loadOffers()
    }
    
    func loadOffers() {
        isLoading = true
        errorMessage = nil
        
        // Simulate loading offers from shared module or repository
        // In a real app, this would come from a use case like GetActiveOffersUseCase
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
            self?.isLoading = false
            self?.offers = [
                Offer(
                    id: UUID(),
                    amount: 50000,
                    feeAmount: 500,
                    type: .sell,
                    counterpartyAlias: "alice_trader",
                    timestamp: Date().addingTimeInterval(-3600)
                ),
                Offer(
                    id: UUID(),
                    amount: 75000,
                    feeAmount: 750,
                    type: .buy,
                    counterpartyAlias: "btc_exchange",
                    timestamp: Date().addingTimeInterval(-7200)
                )
            ]
        }
    }
    
    func cancelOffer(_ offerId: UUID) {
        // In a real app, this would call a use case to cancel the offer
        offers.removeAll { $0.id == offerId }
    }
}

struct Offer: Identifiable, Equatable {
    let id: UUID
    let amount: Int // in satoshis
    let feeAmount: Int // in satoshis
    let type: OfferType // buy or sell
    let counterpartyAlias: String
    let timestamp: Date
    
    enum OfferType: String, Equatable {
        case buy
        case sell
    }
}