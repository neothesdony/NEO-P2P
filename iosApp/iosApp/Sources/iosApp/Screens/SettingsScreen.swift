import SwiftUI

struct SettingsScreen: View {
    @StateObject private var viewModel: SettingsViewModel
    @State private var showingResetIdentityAlert = false
    @State private var showingAddRelaySheet = false
    @State private var newRelayURL = ""
    
    init() {
        // Initialize ViewModel - in a real app, this would come from DI container
        _viewModel = StateObject(wrappedValue: SettingsViewModel())
    }
    
    var body: some View {
        NavigationStack {
            List {
                Section(header: Text("Nostr Relays")) {
                    ForEach(viewModel.relays) { relay in
                        HStack {
                            Image(systemName: relay.isConnected ? "circle.fill" : "circle")
                                .foregroundColor(relay.isConnected ? .green : .gray)
                            Text(relay.url)
                            Spacer()
                        }
                    }
                    .onDelete(perform: viewModel.removeRelay)
                    
                    Button(action: {
                        showingAddRelaySheet = true
                    }) {
                        Label("Add Relay", systemImage: "plus")
                    }
                }
                
                Section(header: Text("Connectivity")) {
                    HStack {
                        Text("TURN Server")
                        Spacer()
                        Text(viewModel.turnServer ?? "Not configured")
                            .foregroundColor(.secondary)
                    }
                    
                    HStack {
                        Text("Default STUN")
                        Spacer()
                        Text("stun:stun.l.google.com:19302")
                            .foregroundColor(.secondary)
                    }
                }
                
                Section(header: Text("Privacy")) {
                    Toggle(isOn: $viewModel.isTorEnabled) {
                        Text("Route through Tor")
                    }
                    
                    Toggle(isOn: $viewModel.isAutoConnectEnabled) {
                        Text("Auto-connect to relays")
                    }
                }
                
                Section(header: Text("About")) {
                    LabeledContent("Version", value: "v1.0.0-alpha")
                    LabeledContent("Network", value: "Nostr + libp2p")
                    LabeledContent("Escrow", value: "Lightning 2-of-3")
                    LabeledContent("Fee", value: "1%")
                }
                
                Section(header: Text("Danger Zone"), footer: Text("This action cannot be undone")) {
                    Button(role: .destructive) {
                        showingResetIdentityAlert = true
                    } label: {
                        Text("Reset Identity")
                    }
                }
                
                Section(header: Text("Fee Wallet")) {
                    VStack(alignment: .leading) {
                        Text("1% commission goes to:")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Text("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh")
                            .font(.body.monospaced())
                            .textSelection(.enabled)
                    }
                }
            }
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: {
                        // More actions menu
                    }) {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .alert("Reset Identity", isPresented: $showingResetIdentityAlert) {
                Button("Cancel", role: .cancel) { }
                Button("Reset", role: .destructive) {
                    viewModel.resetIdentity()
                }
            } message: {
                Text("Permanently destroy keypair, lose escrow access")
            }
            .sheet(isPresented: $showingAddRelaySheet) {
                AddRelaySheet { url in
                    viewModel.addRelay(url)
                    showingAddRelaySheet = false
                }
            }
            .overlay {
                if viewModel.isLoading {
                    ProgressView()
                }
            }
            .onAppear {
                viewModel.loadSettings()
            }
        }
    }
}

struct AddRelaySheet: View {
    @Environment(\.dismiss) private var dismiss
    let onSave: (String) -> Void
    @State private var relayURL = ""
    
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Relay URL", text: $relayURL)
                        .keyboardType(.URL)
                        .autocapitalization(.none)
                }
            }
            .navigationTitle("Add Relay")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave(relayURL)
                        dismiss()
                    }
                    .disabled(relayURL.isEmpty)
                }
            }
        }
    }
}

#Preview {
    SettingsScreen()
}