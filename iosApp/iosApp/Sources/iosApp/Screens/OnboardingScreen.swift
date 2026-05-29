import SwiftUI

struct OnboardingScreen: View {
    @State private var currentStep = 0
    @State private var identityCreated = false
    @State private var seedPhrase = ""
    @State private var showingSeedWarning = false
    
    let totalSteps = 4
    
    var body: some View {
        VStack(spacing: 0) {
            // Progress Indicator
            HStack(spacing: 12) {
                ForEach(0..<totalSteps) { index in
                    Circle()
                        .fill(index < currentStep ? Color.green : index == currentStep ? Color.green.opacity(0.3) : Color.gray.opacity(0.3))
                        .frame(width: 8, height: 8)
                }
            }
            .padding(.vertical, 24)
            
            TabView(selection: $currentStep) {
                // Step 1: Welcome
                VStack(spacing: 24) {
                    Image(systemName: "lock.shield.fill")
                        .font(.system(size: 60))
                        .foregroundColor(.green)
                    
                    VStack(spacing: 16) {
                        Text("Welcome to NEO-P2P")
                            .font(.title2)
                            .fontWeight(.bold)
                        
                        Text("Trade Bitcoin peer-to-peer without intermediaries, KYC, or central servers")
                            .font(.body)
                            .multilineTextAlignment(.center)
                            .foregroundColor(.secondary)
                    }
                    .padding(.horizontal, 40)
                }
                .tag(0)
                
                // Step 2: Create Identity
                VStack(spacing: 24) {
                    Image(systemName: "key.fill")
                        .font(.system(size: 50))
                        .foregroundColor(.green)
                    
                    VStack(spacing: 16) {
                        Text("Create Your Identity")
                            .font(.title2)
                            .fontWeight(.bold)
                        
                        Text("Your identity is a cryptographic keypair stored securely on your device. No personal information is required.")
                            .font(.body)
                            .multilineTextAlignment(.center)
                            .foregroundColor(.secondary)
                    }
                    .padding(.horizontal, 40)
                    
                    Button(action: {
                        // In real app: generate identity and move to next step
                        identityCreated = true
                        currentStep = 1
                    }) {
                        Text("Create Identity")
                            .fontWeight(.semibold)
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(identityCreated ? Color.green : Color.green.opacity(0.5))
                            .foregroundColor(.white)
                            .cornerRadius(12)
                    }
                    .disabled(!identityCreated)
                    .padding(.horizontal, 40)
                }
                .tag(1)
                
                // Step 3: Backup Seed
                VStack(spacing: 24) {
                    Image(systemName: "note.text.badge.plus")
                        .font(.system(size: 50))
                        .foregroundColor(.green)
                    
                    VStack(spacing: 16) {
                        Text("Backup Your Seed Phrase")
                            .font(.title2)
                            .fontWeight(.bold)
                        
                        Text("This seed phrase is the only way to recover your identity and funds. Store it securely and never share it.")
                            .font(.body)
                            .multilineTextAlignment(.center)
                            .foregroundColor(.secondary)
                    }
                    .padding(.horizontal, 40)
                    
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Your seed phrase:")
                            .font(.headline)
                        
                        Text(seedPhrase)
                            .font(.body.monospaced())
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding()
                            .background(Color(.systemBackground))
                            .cornerRadius(8)
                            .textSelection(.enabled)
                    }
                    .padding(.horizontal, 40)
                    
                    Button(action: {
                        showingSeedWarning = true
                    }) {
                        Text("I've safely stored my seed phrase")
                            .fontWeight(.semibold)
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color.green)
                            .foregroundColor(.white)
                            .cornerRadius(12)
                    }
                    .padding(.horizontal, 40)
                }
                .tag(2)
                
                // Step 4: Finish
                VStack(spacing: 24) {
                    Image(systemName: "checkmark.shield.fill")
                        .font(.system(size: 60))
                        .foregroundColor(.green)
                    
                    VStack(spacing: 16) {
                        Text("You're Ready to Trade!")
                            .font(.title2)
                            .fontWeight(.bold)
                        
                        Text("Your identity is secure and backed up. Start exploring peer-to-peer Bitcoin trades in Indonesia.")
                            .font(.body)
                            .multilineTextAlignment(.center)
                            .foregroundColor(.secondary)
                    }
                    .padding(.horizontal, 40)
                    
                    Button(action: {
                        // In real app: finish onboarding and go to main app
                    }) {
                        Text("Start Trading")
                            .fontWeight(.semibold)
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color.green)
                            .foregroundColor(.white)
                            .cornerRadius(12)
                    }
                    .padding(.horizontal, 40)
                }
                .tag(3)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .animation(.easeInOut, value: currentStep)
            
            Spacer(minLength: 0)
            
            // Navigation buttons
            HStack {
                if currentStep > 0 {
                    Button(action: {
                        withAnimation {
                            currentStep -= 1
                        }
                    }) {
                        Text("Back")
                            .fontWeight(.medium)
                    }
                }
                
                Spacer()
                
                Button(action: {
                    if currentStep < totalSteps - 1 {
                        withAnimation {
                            currentStep += 1
                        }
                    }
                }) {
                    Text(currentStep == totalSteps - 1 ? "Finish" : "Next")
                        .fontWeight(.semibold)
                        .frame(minWidth: 80)
                        .padding()
                        .background(Color.green)
                        .foregroundColor(.white)
                        .cornerRadius(12)
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
            .background(Color(.systemBackground))
        }
        .background(Color(.systemGroupedBackground))
        .alert("Important!", isPresented: $showingSeedWarning) {
            Button("I understand", role: .cancel) { }
        } message: {
            VStack(alignment: .leading, spacing: 12) {
                Text("Never share this phrase with anyone.")
                    .font(.headline)
                Text("Anyone with this phrase can access your funds.")
                    .font(.subheadline)
                Text("We cannot recover it for you if lost.")
                    .font(.subheadline)
            }
        }
    }
}

#Preview {
    OnboardingScreen()
}