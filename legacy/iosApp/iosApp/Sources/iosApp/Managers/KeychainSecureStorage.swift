import Foundation
import Security

/// Protocol for secure key-value storage
protocol SecureStorage {
    /// Store a value securely in the keychain
    /// - Parameters:
    ///   - value: The string value to store
    ///   - key: The key to store the value under
    /// - Returns: True if successful, false otherwise
    func store(_ value: String, forKey key: String) -> Bool
    
    /// Retrieve a value from secure storage
    /// - Parameter key: The key to retrieve
    /// - Returns: The stored value, or nil if not found or on error
    func retrieve(forKey key: String) -> String?
    
    /// Delete a value from secure storage
    /// - Parameter key: The key to delete
    /// - Returns: True if successful, false otherwise
    func delete(forKey key: String) -> Bool
    
    /// Check if a value exists for the given key
    /// - Parameter key: The key to check
    /// - Returns: True if a value exists, false otherwise
    func contains(_ key: String) -> Bool
}

/// Concrete implementation using iOS Keychain
final class KeychainSecureStorage: SecureStorage {
    private let service: String
    private let accessGroup: String?
    
    init(service: String = "com.neop2p.iosApp", accessGroup: String? = nil) {
        self.service = service
        self.accessGroup = accessGroup
    }
    
    func store(_ value: String, forKey key: String) -> Bool {
        guard let data = value.data(using: .utf8) else { return false }
        
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: key,
            kSecValueData: data
        ]
        
        // Delete any existing item first
        SecItemDelete(query as CFDictionary)
        
        // Add the new item
        let status = SecItemAdd(query as CFDictionary, nil)
        return status == errSecSuccess
    }
    
    func retrieve(forKey key: String) -> String? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: key,
            kSecReturnData: kCFBooleanTrue,
            kSecMatchLimit: kSecMatchLimitOne
        ]
        
        var dataTypeRef: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &dataTypeRef)
        
        guard status == errSecSuccess,
              let retrievedData = dataTypeRef as? Data,
              let value = String(data: retrievedData, encoding: .utf8) else {
            return nil
        }
        
        return value
    }
    
    func delete(forKey key: String) -> Bool {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: key
        ]
        
        let status = SecItemDelete(query as CFDictionary)
        return status == errSecSuccess
    }
    
    func contains(_ key: String) -> Bool {
        return retrieve(forKey: key) != nil
    }
}

/// Helper for storing codable objects securely
extension SecureStorage {
    /// Store a Codable object securely
    /// - Parameters:
    ///   - value: The Codable object to store
    ///   - key: The key to store the object under
    /// - Returns: True if successful, false otherwise
    func store<T: Codable>(_ value: T, forKey key: String) -> Bool {
        do {
            let data = try JSONEncoder().encode(value)
            let jsonString = String(data: data, encoding: .utf8) ?? ""
            return store(jsonString, forKey: key)
        } catch {
            return false
        }
    }
    
    /// Retrieve a Codable object from secure storage
    /// - Parameter key: The key to retrieve
    /// - Returns: The decoded object, or nil if not found or on error
    func retrieve<T: Codable>(forKey key: String) -> T? {
        guard let jsonString = retrieve(forKey: key),
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

/// ViewModel extension showing how to use secure storage
extension SettingsViewModel {
    /// Save a setting value securely
    /// - Parameters:
    ///   - value: The value to save
    ///   - key: The storage key
    ///   - storage: The secure storage instance to use
    func saveSetting<T: Codable>(_ value: T, forKey key: String, storage: SecureStorage) {
        let success = storage.store(value, forKey: key)
        if !success {
            errorMessage = "Failed to save setting: \(key)"
        }
        objectWillChange.send()
    }
    
    /// Load a setting value from secure storage
    /// - Parameters:
    ///   - key: The storage key
    ///   - storage: The secure storage instance to use
    ///   - defaultValue: The value to return if not found
    /// - Returns: The stored value or defaultValue
    func loadSetting<T: Codable>(forKey key: String, storage: SecureStorage, defaultValue: T) -> T {
        return storage.retrieve(forKey: key) ?? defaultValue
    }
}