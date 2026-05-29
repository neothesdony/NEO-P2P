import Foundation
import WebRTC

/// Protocol for WebRTC peer-to-peer connections
protocol WebRTCManager {
    /// Initialize the WebRTC manager
    func initialize()
    
    /// Create a new peer connection with the given configuration
    /// - Parameters:
    ///   - configuration: ICE server configuration
    ///   - delegate: Object to receive connection events
    /// - Returns: New peer connection
    func createPeerConnection(
        configuration: RTCConfiguration,
        delegate: RTCPeerConnectionDelegate
    ) -> RTCPeerConnection
    
    /// Create a data channel for sending/receiving arbitrary data
    /// - Parameters:
    ///   - peerConnection: The peer connection to attach to
    ///   - label: Identifier for the data channel
    ///   - configuration: Optional configuration for the channel
    /// - Returns: New data channel
    func createDataChannel(
        from peerConnection: RTCPeerConnection,
        label: String,
        configuration: RTCDataChannelConfiguration?
    ) -> RTCDataChannel
    
    /// Get the current ICE connection state
    /// - Parameter peerConnection: The peer connection to check
    /// - Returns: Current ICE connection state
    func iceConnectionState(of peerConnection: RTCPeerConnection) -> RTCIceConnectionState
    
    /// Close a peer connection and release resources
    /// - Parameter peerConnection: The connection to close
    func close(_ peerConnection: RTCPeerConnection)
}

/// Concrete implementation using WebRTC framework
final class WebRTCManagerImpl: WebRTCManager {
    private let factory: RTCPeerConnectionFactory
    
    init() {
        // Initialize WebRTC framework
        let encoderFactory = DefaultVideoEncoderFactory()
        let decoderFactory = DefaultVideoDecoderFactory()
        factory = RTCPeerConnectionFactory(
            encoderFactory: encoderFactory,
            decoderFactory: decoderFactory
        )
    }
    
    func initialize() {
        // Framework is initialized in init()
    }
    
    func createPeerConnection(
        configuration: RTCConfiguration,
        delegate: RTCPeerConnectionDelegate
    ) -> RTCPeerConnection {
        return factory.peerConnection(with: configuration, delegate: delegate)
    }
    
    func createDataChannel(
        from peerConnection: RTCPeerConnection,
        label: String,
        configuration: RTCDataChannelConfiguration?
    ) -> RTCDataChannel {
        let config = configuration ?? RTCDataChannelConfiguration()
        return peerConnection.dataChannel(forLabel: label, configuration: config)
    }
    
    func iceConnectionState(of peerConnection: RTCPeerConnection) -> RTCIceConnectionState {
        return peerConnection.iceConnectionState
    }
    
    func close(_ peerConnection: RTCPeerConnection) {
        peerConnection.close()
    }
}

/// Helper for sending files over WebRTC data channels
final class WebRTCFileTransfer {
    private weak var dataChannel: RTCDataChannel?
    private let queue = DispatchQueue(label: "com.neop2p.webrtc.filetransfer")
    
    init(dataChannel: RTCDataChannel) {
        self.dataChannel = dataChannel
        setupDataChannel()
    }
    
    private func setupDataChannel() {
        dataChannel?.delegate = self
    }
    
    /// Send a file over the data channel
    /// - Parameters:
    ///   - fileURL: URL to the file to send
    ///   - completion: Callback when send completes
    func sendFile(at fileURL: URL, completion: @escaping (Error?) -> Void) {
        queue.async {
            do {
                let fileData = try Data(contentsOf: fileURL)
                let fileName = fileURL.lastPathComponent
                
                // Create message header: [4-byte length][filename][file-data]
                var header = Data()
                var nameData = fileName.data(using: .utf8) ?? Data()
                
                // Ensure name length fits in 2 bytes (max 65535 chars)
                let nameLength = min(UInt16(nameData.count), 65535)
                withUnsafeBytes(of: nameLength.bigEndian) { header.append($0, count: 2) }
                header.append(nameData)
                
                // Combine header and file data
                var fullMessage = header
                fullMessage.append(fileData)
                
                // Send as binary data
                let buffer = RTCDataBuffer(data: fullMessage, isBinary: true)
                self.dataChannel?.sendData(buffer)
                
                DispatchQueue.main.async {
                    completion(nil)
                }
            } catch {
                DispatchQueue.main.async {
                    completion(error)
                }
            }
        }
    }
}

/// Extension to handle data channel events
extension WebRTCFileTransfer: RTCDataChannelDelegate {
    func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {
        // Handle state changes if needed
    }
    
    func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        // Handle incoming data
        // Parse the message format: [2-byte name length][filename][file-data]
        let data = buffer.data
        
        guard data.count >= 2 else { return }
        
        // Read name length (first 2 bytes, big endian)
        var nameLengthBytes: UInt16 = 0
        data.copyBytes(to: &nameLengthBytes, from: 0..<2)
        nameLengthBytes = UInt16(bigEndian: nameLengthBytes)
        
        let nameStart = 2
        let nameEnd = min(nameStart + Int(nameLengthBytes), data.count)
        guard nameEnd > nameStart else { return }
        
        let fileNameData = data[nameStart..<nameEnd]
        guard let fileName = String(data: fileNameData, encoding: .utf8) else { return }
        
        let fileDataStart = nameEnd
        let fileData = data[fileDataStart..<data.count]
        
        // Here you would save the file or process it
        // For now, just print what we received
        print("Received file: \(fileName), size: \(fileData.count) bytes")
        
        // Notify interested parties (could use NotificationCenter, Combine, etc.)
        NotificationCenter.default.post(
            name: .webRTCFileReceived,
            object: nil,
            userInfo: [
                "fileName": fileName,
                "fileData": Data(fileData)
            ]
        )
    }
}

/// Notification extension for file transfers
extension Notification.Name {
    static let webRTCFileReceived = Notification.Name("webRTCFileReceived")
}

/// ViewModel extension showing how to use WebRTC for file transfer
extension SettingsViewModel {
    /// Example of how to initiate a WebRTC file transfer
    /// This would typically be called from ChatViewModel when sending payment proof
    func sendPaymentProof(
        fileURL: URL,
        to peerConnection: RTCPeerConnection,
        using webRTCManager: WebRTCManager,
        completion: @escaping (Error?) -> Void
    ) {
        // Create data channel for file transfer
        let dataChannel = webRTCManager.createDataChannel(
            from: peerConnection,
            label: "filetransfer",
            configuration: nil
        )
        
        // Configure the data channel
        dataChannel.delegate = WebRTCFileTransfer(dataChannel: dataChannel)
        
        // Send the file
        let fileTransfer = WebRTCFileTransfer(dataChannel: dataChannel)
        fileTransfer.sendFile(at: fileURL, completion: completion)
    }
}