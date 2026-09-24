package com.neop2p.ui.util

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.neop2p.R

/**
 * P0-4 / audit #10: one authenticate-then-run gate for every seed-revealing
 * action (recovery-phrase reveal, identity export, identity import). Mirrors
 * the reveal prompt at `SettingsScreen.kt` so the copy is identical.
 *
 * A device with no lock-screen credential cannot run the prompt (it fails
 * instantly with ERROR_NO_BIOMETRICS). The app must never lock a user out of
 * their own data — on such a device the phone is already open, so the action
 * proceeds directly. Genuine prompt failures (cancel, lockout) call
 * [onUnavailable].
 */
fun authenticateForSecret(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onUnavailable: () -> Unit = {}
) {
    val canAuth = BiometricManager.from(activity).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    ) == BiometricManager.BIOMETRIC_SUCCESS
    if (!canAuth) {
        onSuccess()
        return
    }

    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onUnavailable()
            }
        }
    )
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(activity.getString(R.string.settings_seed_auth_required))
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build()
    prompt.authenticate(promptInfo)
}
