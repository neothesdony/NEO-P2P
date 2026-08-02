import Foundation
import Combine

// MARK: - Dependencies Protocol

/// Protocol defining the dependencies needed by SettingsViewModel
protocol SettingsDependencies {
    var biometricAuthenticator: BiometricAuthenticator { get }
    var secureStorage: SecureStorage { get }
    var torManager: TorManager { get }
    
    // In a real app, you'd also have:
    // var identityManager: IdentityManager
    // var nostrClient: NostrClient
    // etc.
}

// MARK: - Default Dependencies Implementation

/// Default implementation of dependencies for production use
struct ProductionSettingsDependencies: SettingsDependencies {
    let biometricAuthenticator: BiometricAuthenticator
    let secureStorage: SecureStorage
    let torManager: TorManager
    
    init() {
        self.biometricAuthenticator = LAContextBiometricAuthenticator()
        self.secureStorage = KeychainSecureStorage(service: "com.neop2p.neoP2P")
        self.torManager = NetworkExtensionTorManager(storage: self.secureStorage)
    }
}

// MARK: - Updated SettingsViewModel with DI

class SettingsViewModel: ObservableObject {
    @Published var relays: [Relay] = []
    @Published var turnServer: String? = nil
    @Published var isTorEnabled: Bool = false
    @Published var isAutoConnectEnabled: Bool = false
    @Published var isLoading: Bool = false
    @Published var errorMessage: String? = nil
    @Published var isAuthenticating: Bool = false
    
    private var cancellables = Set<AnyCancellable>()
    private let dependencies: SettingsDependencies
    private let settingsRepository: SettingsRepositoryProtocol
    
    // MARK: - Initialization with Dependency Injection
    
    init(
        dependencies: SettingsDependencies = ProductionSettingsDependencies(),
        settingsRepository: SettingsRepositoryProtocol = ProductionSettingsRepository()
    ) {
        self.dependencies = dependencies
        self.settingsRepository = settingsRepository
        loadSettings()
    }
    
    // MARK: - Settings Management
    
    func loadSettings() {
        isLoading = true
        errorMessage = nil
        
        // Call into repository (which would talk to shared KMP code)
        settingsRepository.getSettings { [weak self] result in
            DispatchQueue.main.async {
                self?.isLoading = false
                switch result {
                case .success(let settings):
                    self?.updateFromSettings(settings)
                case .failure(let error):
                    self?.errorMessage = error.localizedDescription
                }
            }
        }
    }
    
    private func updateFromSettings(_ settings: SettingsData) {
        self.relays = settings.relays.map { Relay(url: $0.url, isConnected: $0.isConnected) }
        self.turnServer = settings.turnServer
        self.isTorEnabled = settings.isTorEnabled
        self.isAutoConnectEnabled = settings.isAutoConnectEnabled
    }
    
    // MARK: - Relay Management
    
    func addRelay(_ url: String) {
        let newRelay = Relay(url: url, isConnected: false)
        relays.append(newRelay)
        saveSettings()
    }
    
    func removeRelay(at offsets: IndexSet) {
        relays.remove(atOffsets: offsets)
        saveSettings()
    }
    
    // MARK: - Privacy Settings
    
    func toggleTor() {
        authenticateIfNeeded(
            reason: "Authenticate to change Tor settings"
        ) { [weak self] in
            self?.performTorToggle()
        } onFailure: { [weak self] error in
            self?.errorMessage = error.localizedDescription
        }
    }
    
    private func performTorToggle() {
        isLoading = true
        errorMessage = nil
        
        dependencies.torManager.enable { [weak self] result in
            DispatchQueue.main.async {
                self?.isLoading = false
                switch result {
                case .success:
                    self?.isTorEnabled.toggle()
                    self?.saveSettings()
                case .failure(let error):
                    self?.errorMessage = "Failed to toggle Tor: \(error.localizedDescription)"
                }
            }
        }
    }
    
    func toggleAutoConnect() {
        isAutoConnectEnabled.toggle()
        saveSettings()
    }
    
    // MARK: - Identity Reset
    
    func resetIdentity() {
        authenticateIfNeeded(
            reason: "Authenticate to reset your identity"
        ) { [weak self] in
            self?.performIdentityReset()
        } onFailure: { [weak self] error in
            self?.errorMessage = error.localizedDescription
        }
    }
    
    private func performIdentityReset() {
        isLoading = true
        errorMessage = nil
        
        // In a real app, this would call into shared KMP code to:
        // 1. Delete the identity from secure storage
        // 2. Clear all associated data
        // 3. Potentially generate a new identity
        
        // For demo, we'll simulate the reset
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            self?.isLoading = false
            self?.relays = []
            self?.turnServer = nil
            self?.isTorEnabled = false
            self?.isAutoConnectEnabled = false
            self?.saveSettings()
            
            // Show success message
            self?.errorMessage = "Identity has been reset"
        }
    }
    
    // MARK: - Biometric Authentication Helper
    
    func authenticateIfNeeded(
        reason: String,
        onSuccess: @escaping () -> Void,
        onFailure: @escaping (Error) -> Void
    ) {
        // Check if biometrics are available
        if !dependencies.biometricAuthenticator.isAvailable() {
            onFailure(NSError(
                domain: "BiometricAuthenticator",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: dependencies.biometricAuthenticator.unavailableReason() ?? "Biometrics not available"]
            ))
            return
        }
        
        isAuthenticating = true
        errorMessage = nil
        
        let authResult = dependencies.biometricAuthenticator.authenticate(reason: reason)
        
        DispatchQueue.main.async {
            self.isAuthenticating = false
            
            switch authResult {
            case .success:
                onSuccess()
            case .failure(let error):
                onFailure(error)
            }
        }
    }
    
    // MARK: - Settings Persistence
    
    private func saveSettings() {
        // Save all settings to secure storage
        let settingsData = SettingsData(
            relays: relays.map { RelayData(url: $0.url, isConnected: $0.isConnected) },
            turnServer: turnServer,
            isTorEnabled: isTorEnabled,
            isAutoConnectEnabled: isAutoConnectEnabled
        )
        
        let success = dependencies.secureStorage.store(settingsData, forKey: "app_settings")
        if !success {
            errorMessage = "Failed to save settings"
        }
        
        // Notify observers that settings have changed
        objectWillChange.send()
    }
}

// MARK: - Data Models

struct SettingsData: Equatable {
    let relays: [RelayData]
    let turnServer: String?
    let isTorEnabled: Boolean
    let isAutoConnectEnabled: Boolean
}

struct RelayData: Identifiable, Equatable {
    let id = UUID()
    let url: String
    let isConnected: Boolean
}

// MARK: - Repository Protocol

/// Protocol for settings repository - abstracts data source details
protocol SettingsRepositoryProtocol {
    func getSettings(completion: @escaping (Result<SettingsData, Error>) -> Void)
    func saveSettings(_ settings: SettingsData, completion: @escaping (Result<Void, Error>) -> Void)
}

/// Production implementation that would talk to shared KMP code
final class ProductionSettingsRepository: SettingsRepositoryProtocol {
    // In a real implementation, this would have references to:
    // - Shared KMP modules (identityManager, nostrClient, etc.)
    // - Platform-specific implementations
    
    func getSettings(completion: @escaping (Result<SettingsData, Error>) -> Void) {
        // This is where you'd call into your shared Kotlin Multiplatform code
        // For now, we'll simulate loading from secure storage
        
        // In reality, you might do something like:
        // let sharedSettings = SharedSettingsRepository().getSettings()
        // Then map SharedSettings -> SettingsData
        
        // Simulate loading from secure storage for demo
        let storage = KeychainSecureStorage(service: "com.neop2p.neoP2P")
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            if let storedSettings: SettingsData = storage.retrieve(forKey: "app_settings") {
                completion(.success(storedSettings))
            } else {
                // Return default settings
                let defaultSettings = SettingsData(
                    relays: [
                        RelayData(url: "connect.nostr.pool:50000", isConnected: true),
                        RelayData(url: "hub.neop2p.id:50001", isConnected: true)
                    ],
                    turnServer: "turn:neop2p.id:3478?transport=tcp",
                    isTorEnabled: true,
                    isAutoConnectEnabled: true
                )
                completion(.success(defaultSettings))
            }
        }
    }
    
    func saveSettings(_ settings: SettingsData, completion: @escaping (Result<Void, Error>) -> Void) {
        let storage = KeychainSecureStorage(service: "com.neop2p.neoP2P")
        let success = storage.store(settings, forKey: "app_settings")
        
        DispatchQueue.main.async {
            if success {
                completion(.success(()))
            } else {
                completion(.failure(NSError(domain: "SettingsRepository", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to save settings"])))
            }
        }
    }
}

// MARK: - Preview Helper

extension SettingsViewModel {
    static func preview() -> SettingsViewModel {
        let mockDependencies = MockSettingsDependencies()
        let mockRepository = MockSettingsRepository()
        return SettingsViewModel(dependencies: mockDependencies, settingsRepository: mockRepository)
    }
}

// MARK: - Mocks for Preview

final class MockSettingsDependencies: SettingsDependencies {
    let biometricAuthenticator: BiometricAuthenticator = MockBiometricAuthenticator()
    let secureStorage: SecureStorage = MockSecureStorage()
    let torManager: TorManager = MockTorManager()
}

final class MockBiometricAuthenticator: BiometricAuthenticator {
    func isAvailable() -> Bool { return true }
    func unavailableReason() -> String? { return nil }
    func authenticate(reason: String) -> Result<Void, Error> {
        // Simulate immediate success for preview
        return .success(())
    }
}

final class MockSecureStorage: SecureStorage {
    private var storage: [String: String] = [:]
    
    func store(_ value: String, forKey key: String) -> Bool {
        storage[key] = value
        return true
    }
    
    func retrieve(forKey key: String) -> String? {
        return storage[key]
    }
    
    func delete(forKey key: String) -> Bool {
        storage.removeValue(forKey: key) != nil
        return true
    }
    
    func contains(_ key: String) -> Bool {
        return storage[key] != nil
    }
    
    // For SettingsData storage
    func store<T: Codable>(_ value: T, forKey key: String) -> Bool {
        do {
            let data = try JSONEncoder().encode(value)
            if let jsonString = String(data: data, encoding: .utf8) {
                storage[key] = jsonString
                return true
            }
            return false
        } catch {
            return false
        }
    }
    
    func retrieve<T: Codable>(forKey key: String) -> T? {
        guard let jsonString = storage[key],
              let data = jsonString.data(using: .utf8) else {
            return nil
        }
        
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            return nil
        }
    }
}

final class MockSettingsRepository: SettingsRepositoryProtocol {
    func getSettings(completion: @escaping (Result<SettingsData, Error>) -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            let settings = SettingsData(
                relays: [
                    RelayData(url: "connect.nostr.pool:50000", isConnected: true),
                    RelayData(url: "hub.neop2p.id:50001", isConnected: true)
                ],
                turnServer: "turn:neop2p.id:3478?transport=tcp",
                isTorEnabled: true,
                isAutoConnectEnabled: true
            )
            completion(.success(settings))
        }
    }
    
    func saveSettings(_ settings: SettingsData, completion: @escaping (Result<Void, Error>) -> Void) {
        DispatchQueue.main.async {
            completion(.success(()))
        }
    }
}