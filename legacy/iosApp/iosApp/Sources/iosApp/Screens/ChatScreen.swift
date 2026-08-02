import SwiftUI

struct ChatScreen: View {
    let offerId: String
    let peerId: String
    @Environment(\.presentationMode) var presentationMode
    @StateObject private var viewModel = ChatViewModel()
    @State private var messageText: String = ""
    @State private var showingFilePicker = false
    
    var body: some View {
        Group {
            switch viewModel.uiState {
            case .loading:
                LoadingView()
            case .error(let message):
                ErrorView(message: message) {
                    viewModel.loadMessages()
                }
            case .success(let chatData):
                ChatContentView(
                    messages: chatData.messages,
                    offerId: offerId,
                    peerId: peerId,
                    myPeerId: viewModel.myPeerId,
                    onMessageSent: { viewModel.sendMessage($0) },
                    onFileReceived: { viewModel.handleReceivedFile($0) },
                    onEscrowCreated: { /* Handle escrow creation */ }
                )
            }
        }
        .navigationTitle("Chat with Peer")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button(action: {
                    presentationMode.wrappedValue.dismiss()
                }) {
                    Image(systemName: "chevron.left")
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: {
                    // Create escrow action
                }) {
                    Text("Escrow")
                        .fontWeight(.semibold)
                }
                .disabled(viewModel.myPeerId.isEmpty || messageText.isEmpty) // Simplified condition
            }
        }
        .overlay {
            if viewModel.isLoading {
                ProgressView()
            }
        }
        .onAppear {
            viewModel.loadMessages()
        }
        .safeAreaInset(edge: .bottom) {
            ChatInputView(
                messageText: $messageText,
                isSending: viewModel.isSending,
                onSend: {
                    viewModel.sendMessage(messageText)
                    messageText = ""
                },
                onAttach: {
                    showingFilePicker = true
                }
            )
            .background(.ultraThinMaterial)
        }
        .sheet(isPresented: $showingFilePicker) {
            // File picker would go here
            Text("File picker placeholder")
        }
    }
}

struct ChatContentView: View {
    let messages: [ChatMessage]
    let offerId: String
    let peerId: String
    let myPeerId: String
    let onMessageSent: (String) -> Void
    let onFileReceived: (ReceivedFile) -> Void
    let onEscrowCreated: (String) -> Void
    
    var body: some View {
        ScrollViewReader { proxy in
            List {
                ForEach(messages) { message in
                    ChatMessageBubble(
                        message: message,
                        isMine: message.senderPeerId == myPeerId
                    )
                    .id(message.id)
                }
            }
            .listStyle(.plain)
            .onChange(of: messages) { _ in
                if let lastMessage = messages.last {
                    withAnimation {
                        proxy.scrollTo(lastMessage.id, anchor: .bottom)
                    }
                }
            }
        }
    }
}

struct ChatMessageBubble: View {
    let message: ChatMessage
    let isMine: Bool
    
    var body: some View {
        HStack {
            if isMine {
                Spacer()
            }
            
            VStack(alignment: isMine ? .trailing : .leading, spacing: 4) {
                // Message text
                Text(message.text)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        isMine ? Color.green.opacity(0.2) : Color(.systemGray5)
                    )
                    .foregroundColor(isMine ? .green : .primary)
                    .cornerRadius(16)
                
                // File attachment indicator
                if message.hasFileAttachment {
                    HStack {
                        Image(systemName: "paperclip")
                        Text("Payment proof")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .padding(.horizontal, 8)
                }
                
                // Timestamp and status
                HStack(spacing: 4) {
                    Text(message.timeAgo)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    
                    if !isMine && !message.isRead {
                        Circle()
                            .fill(Color.green)
                            .frame(width: 8, height: 8)
                    }
                }
            }
            
            if !isMine {
                Spacer()
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
    }
}

struct ChatInputView: View {
    @Binding var messageText: String
    let isSending: Bool
    let onSend: () -> Void
    let onAttach: () -> Void
    
    var body: some View {
        VStack(spacing: 8) {
            Divider()
            
            HStack(spacing: 12) {
                Button(action: onAttach) {
                    Image(systemName: "paperclip")
                        .font(.system(size: 20))
                        .frame(width: 36, height: 36)
                        .background(Color(.systemGray5))
                        .clipShape(Circle())
                }
                
                TextField("Message...", text: $messageText)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(Color(.systemGray6))
                    .cornerRadius(20)
                    .overlay(
                        RoundedRectangle(cornerRadius: 20)
                            .stroke(Color(.systemGray4), lineWidth: 0.5)
                    )
                    .submitLabel(.send)
                    .onSubmit {
                        onSend()
                    }
                
                Button(action: onSend) {
                    Image(systemName: "paperplane.fill")
                        .font(.system(size: 20))
                        .frame(width: 36, height: 36)
                        .background(!messageText.isEmpty && !isSending ? Color.green : Color(.systemGray4))
                        .foregroundColor(.white)
                        .clipShape(Circle())
                }
                .disabled(messageText.isEmpty || isSending)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .background(.ultraThinMaterial)
    }
}

struct LoadingView: View {
    var body: some View {
        VStack {
            ProgressView()
                .scaleEffect(1.5)
            Text("Loading chat...")
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

class ChatViewModel: ObservableObject {
    enum UiState {
        case loading
        case error(String)
        case success(messages: [ChatMessage])
    }
    
    @Published var uiState: UiState = .loading
    @Published var myPeerId: String = ""
    @Published var isSending: Bool = false
    @Published var isLoading: Bool = false
    
    init() {
        loadMessages()
    }
    
    func loadMessages() {
        uiState = .loading
        isLoading = true
        
        // Simulate loading from shared module or repository
        // In a real app, this would come from a use case like GetChatMessagesUseCase
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
            let sampleMessages = [
                ChatMessage(
                    id: UUID(),
                    messageId: "msg_1",
                    offerId: "offer_123",
                    senderPeerId: "peer_abcdef123456",
                    senderNickname: "Trader_Budi",
                    text: "Hai, ini BTC asli. Escrow sudah saya buat.",
                    timestamp: Date().addingTimeInterval(-120),
                    isRead: true
                ),
                ChatMessage(
                    id: UUID(),
                    messageId: "msg_2",
                    offerId: "offer_123",
                    senderPeerId: "0123456789abcdef", // This would be our peer ID in real app
                    senderNickname: "", // Our nickname
                    text: "Baik, saya transfer lewat BCA sekarang.",
                    timestamp: Date().addingTimeInterval(-60),
                    isRead: false
                )
            ]
            
            // Set our peer ID (would come from identity manager in real app)
            self?.myPeerId = "0123456789abcdef"
            self?.uiState = .success(messages: sampleMessages)
            self?.isLoading = false
        }
    }
    
    func sendMessage(_ text: String) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        
        isSending = true
        
        // Simulate sending message
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            let newMessage = ChatMessage(
                id: UUID(),
                messageId: "msg_\(Int(Date().timeIntervalSince1970))",
                offerId: "offer_123",
                senderPeerId: self?.myPeerId ?? "",
                senderNickname: "", // Our nickname
                text: text,
                timestamp: Date(),
                isRead: false
            )
            
            switch self?.uiState {
            case .success(var messages):
                messages.append(newMessage)
                self?.uiState = .success(messages: messages)
            default:
                break
            }
            
            self?.isSending = false
        }
    }
    
    func handleReceivedFile(_ file: ReceivedFile) {
        // Handle receiving a file (payment proof, etc.)
        // For now, just acknowledge
        print("Received file: \(file.fileName)")
    }
}

// MARK: - Data Models

struct ChatMessage: Identifiable, Equatable {
    let id: UUID
    let messageId: String
    let offerId: String
    let senderPeerId: String
    let senderNickname: String
    let text: String
    let timestamp: Date
    let isRead: Bool
    let hasFileAttachment: Bool
    
    init(id: UUID = UUID(),
         messageId: String,
         offerId: String,
         senderPeerId: String,
         senderNickname: String,
         text: String,
         timestamp: Date,
         isRead: Bool = false,
         hasFileAttachment: Bool = false) {
        self.id = id
        self.messageId = messageId
        self.offerId = offerId
        self.senderPeerId = senderPeerId
        self.senderNickname = senderNickname
        self.text = text
        self.timestamp = timestamp
        self.isRead = isRead
        self.hasFileAttachment = hasFileAttachment
    }
    
    var timeAgo: String {
        let calendar = Calendar.current
        let now = Date()
        let components = calendar.dateComponents([.second, .minute, .hour, .day], from: timestamp, to: now)
        
        if let day = components.day, day > 0 {
            return day == 1 ? "1 day ago" : "\(day) days ago"
        } else if let hour = components.hour, hour > 0 {
            return hour == 1 ? "1 hour ago" : "\(hour) hours ago"
        } else if let minute = components.minute, minute > 0 {
            return minute == 1 ? "1 minute ago" : "\(minute) minutes ago"
        } else {
            return "just now"
        }
    }
}

struct ReceivedFile: Identifiable, Equatable {
    let id = UUID()
    let fromPeerId: String
    let fileName: String
    let data: Data
    let mimeType: String
}

#Preview {
    ChatScreen(offerId: "offer_123", peerId: "peer_abcdef")
}