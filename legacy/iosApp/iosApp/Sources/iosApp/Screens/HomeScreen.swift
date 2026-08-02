import SwiftUI

struct HomeScreen: View {
    @StateObject private var viewModel: HomeViewModel
    @State private var showingCreateOfferSheet = false
    
    init() {
        _viewModel = StateObject(wrappedValue: HomeViewModel())
    }
    
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if viewModel.isLoading {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let error = viewModel.errorMessage {
                    VStack {
                        Text(error)
                            .foregroundColor(.red)
                            .padding()
                        Button("Retry") {
                            viewModel.loadOffers()
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if viewModel.offers.isEmpty {
                    VStack(spacing: 16) {
                        Image(systemName: "bolt.fill")
                            .font(.system(size: 48))
                            .foregroundColor(.secondary)
                        Text("No active offers")
                            .font(.headline)
                        Text("Create an offer to start trading")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                        Button(action: {
                            showingCreateOfferSheet = true
                        }) {
                            Text("Create Offer")
                                .fontWeight(.semibold)
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(Color.green)
                                .foregroundColor(.white)
                                .cornerRadius(12)
                        }
                        .padding(.horizontal, 32)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(viewModel.offers) { offer in
                            OfferCardView(offer: offer)
                                .swipeActions {
                                    Button(role: .destructive) {
                                        viewModel.cancelOffer(offer.id)
                                    } label: {
                                        Label("Cancel", systemImage: "xmark.bin.fill")
                                    }
                                }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("NEO-P2P")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: {
                        showingCreateOfferSheet = true
                    }) {
                        Image(systemName: "plus")
                    }
                }
            }
            .sheet(isPresented: $showingCreateOfferSheet) {
                CreateOfferScreen()
            }
            .onAppear {
                viewModel.loadOffers()
            }
        }
    }
}

struct OfferCardView: View {
    let offer: Offer
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading) {
                    Text(offer.type == .buy ? "BUY" : "SELL")
                        .font(.caption)
                        .fontWeight(.bold)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(offer.type == .buy ? Color.blue.opacity(0.2) : Color.green.opacity(0.2))
                        .foregroundColor(offer.type == .buy ? .blue : .green)
                        .cornerRadius(4)
                    Text("\(offer.amount.formatted()) SATS")
                        .font(.title2)
                        .fontWeight(.bold)
                }
                Spacer()
                VStack(alignment: .trailing) {
                    Text("@\(offer.counterpartyAlias)")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(offer.timestamp, style: .time)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            
            Divider()
            
            HStack {
                Label {
                    Text("Fee: 1% (\(offer.feeAmount.formatted()) SATS)")
                } icon: {
                    Image(systemName: "percent")
                }
                Spacer()
                Label {
                    Text("Escrow: 2-of-3")
                } icon: {
                    Image(systemName: "lock.shield")
                }
            }
            .font(.caption)
            .foregroundColor(.secondary)
        }
        .padding()
        .background(Color(.systemBackground))
        .cornerRadius(16)
        .shadow(radius: 2)
        .padding(.horizontal)
    }
}

#Preview {
    HomeScreen()
}