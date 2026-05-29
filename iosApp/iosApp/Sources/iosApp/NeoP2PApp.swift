import SwiftUI
import FirebaseCore

@main
struct NeoP2PApp: App {
    // Initialize Firebase
    init() {
        FirebaseApp.configure()
    }
    
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    @State private var selection: Tab = .home
    
    enum Tab {
        case home
        profile
        escrow
        chat
        settings
    }
    
    var body: some View {
        TabView(selection: $selection) {
            HomeScreen()
                .tabItem {
                    Label("Home", systemImage: "house")
                }
                .tag(Tab.home)
            
            ProfileScreen()
                .tabItem {
                    Label("Profile", systemImage: "person")
                }
                .tag(Tab.profile)
            
            // Escrow screen needs an escrowId - for now we'll use a placeholder
            // In a real app, this would navigate from offer detail or chat
            EscrowScreen(escrowId: "escrow_placeholder")
                .tabItem {
                    Label("Escrow", systemImage: "lock.shield")
                }
                .tag(Tab.escrow)
            
            // Chat screen needs offerId and peerId - for now we'll use placeholders
            // In a real app, this would navigate from offer detail or home
            ChatScreen(offerId: "offer_placeholder", peerId: "peer_placeholder")
                .tabItem {
                    Label("Chat", systemImage: "bubble.left.and.bubble.right")
                }
                .tag(Tab.chat)
            
            SettingsScreen()
                .tabItem {
                    Label("Settings", systemImage: "gear")
                }
                .tag(Tab.settings)
        }
        .accentColor(.green) // Match the NEO-P2P brand color
    }
}