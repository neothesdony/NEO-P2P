import Foundation
import LocalAuthentication

/// Protocol for biometric authentication - allows for easy mocking/testing
protocol BiometricAuthenticator {
    /// Check if biometric authentication is available on this device
    /// - Returns: True if biometrics can be used, false otherwise
    func isAvailable() -> Bool
    
    /// Get the localized reason string for why biometrics aren't available
    /// - Returns: Human-readable explanation if not available, nil if available
    func unavailableReason() -> String?
    
    /// Authenticate the user using biometrics or device passcode
    /// - Parameters:
    ///   - reason: Localized prompt to show the user
    /// - Returns: Result indicating success or failure
    func authenticate(reason: String) -> Result<Void, Error>
}

/// Concrete implementation using Apple's LocalAuthentication framework
final class LAContextBiometricAuthenticator: BiometricAuthenticator {
    private let context: LAContext
    
    init(context: LAContext = LAContext()) {
        self.context = context
    }
    
    func isAvailable() -> Bool {
        return context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
    }
    
    func unavailableReason() -> String? {
        var error: NSError?
        let canEvaluate = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
        
        if canEvaluate {
            return nil
        }
        
        guard let error = error else {
            return "Biometric authentication is not available"
        }
        
        switch error.code {
        case LAError.biometryNotAvailable.rawValue:
            return "No biometric sensor available on this device"
        case LAError.biometryLockout.rawValue:
            return "Biometric authentication is locked out"
        case LAError.passcodeNotSet.rawValue:
            return "Device passcode is not set"
        case LAError.biometryNotEnrolled.rawValue:
            return "No biometric identities enrolled"
        default:
            return "Biometric authentication is not available: \(error.localizedDescription)"
        }
    }
    
    func authenticate(reason: String) -> Result<Void, Error> {
        // Note: This is a synchronous wrapper around an async API
        // In a real app, you might want to make this async or use callbacks/publishers
        let semaphore = DispatchSemaphore(value: 0)
        var result: Result<Void, Error>? = nil
        
        context.evaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            localizedReason: reason
        ) { success, error in
            if let error = error {
                result = .failure(error)
            } else {
                result = .success(())
            }
            semaphore.signal()
        }
        
        _ = semaphore.wait(timeout: .distantFuture)
        return result ?? .failure(NSError(domain: LAErrorDomain, code: LAError.authenticationFailed.rawValue, userInfo: [NSLocalizedDescriptionKey: "Authentication failed"]))
    }
}

/// ViewModel extension showing how to use biometric authentication
extension SettingsViewModel {
    /// Attempt to authenticate user before performing sensitive operations
    /// - Parameters:
    ///   - reason: Explanation for why authentication is needed
    ///   - onSuccess: Callback to execute if authentication succeeds
    ///   - onFailure: Callback to execute if authentication fails or is cancelled
    func authenticateIfNeeded(
        reason: String,
        onSuccess: @escaping () -> Void,
        onFailure: @escaping (Error) -> Void
    ) {
        let authenticator = LAContextBiometricAuthenticator()
        
        if !authenticator.isAvailable() {
            onFailure(NSError(domain: "BiometricAuthenticator", code: -1, userInfo: [NSLocalizedDescriptionKey: authenticator.unavailableReason() ?? "Biometrics not available"]))
            return
        }
        
        let authResult = authenticator.authenticate(reason: reason)
        
        switch authResult {
        case .success:
            onSuccess()
        case .failure(let error):
            onFailure(error)
        }
    }
}