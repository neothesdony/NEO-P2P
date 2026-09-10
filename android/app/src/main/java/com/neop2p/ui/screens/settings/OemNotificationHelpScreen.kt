package com.neop2p.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.neop2p.R
import com.neop2p.ui.theme.NeoP2PTheme

/**
 * Settings → "Aktifkan Notifikasi (Ponsel ini)".
 *
 * Static per-OEM checklist (dontkillmyapp.com pattern): Android OEMs kill
 * background apps aggressively, and a killed P2P service silently stops
 * receiving relay messages. The user fixes it ONCE per device, here, with
 * exact menu paths for their brand. No permissions are requested here —
 * this is a manual checklist, not a runtime prompt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OemNotificationHelpScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val brand = oemBrand(Build.MANUFACTURER)

    NeoP2PTheme {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.settings_oem_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.general_back)
                            )
                        }
                    }
                )
            },
            content = { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(R.string.settings_oem_desc, oemBrandLabel(context, brand)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))

                    // Brand-specific checklist (raw menu paths, dontkillmyapp-style).
                    val steps: List<String> = when (brand) {
                        OemBrand.XIAOMI -> listOf(
                            stringResource(R.string.settings_oem_xiaomi_1),
                            stringResource(R.string.settings_oem_xiaomi_2),
                            stringResource(R.string.settings_oem_xiaomi_3),
                            stringResource(R.string.settings_oem_xiaomi_4)
                        )
                        OemBrand.SAMSUNG -> listOf(
                            stringResource(R.string.settings_oem_samsung_1),
                            stringResource(R.string.settings_oem_samsung_2),
                            stringResource(R.string.settings_oem_samsung_3),
                            stringResource(R.string.settings_oem_samsung_4)
                        )
                        OemBrand.OPPO -> listOf(
                            stringResource(R.string.settings_oem_oppo_1),
                            stringResource(R.string.settings_oem_oppo_2),
                            stringResource(R.string.settings_oem_oppo_3)
                        )
                        OemBrand.VIVO -> listOf(
                            stringResource(R.string.settings_oem_vivo_1),
                            stringResource(R.string.settings_oem_vivo_2),
                            stringResource(R.string.settings_oem_vivo_3)
                        )
                        OemBrand.HUAWEI -> listOf(
                            stringResource(R.string.settings_oem_huawei_1),
                            stringResource(R.string.settings_oem_huawei_2),
                            stringResource(R.string.settings_oem_huawei_3)
                        )
                        else -> listOf(
                            stringResource(R.string.settings_oem_generic_1),
                            stringResource(R.string.settings_oem_generic_2),
                            stringResource(R.string.settings_oem_generic_3)
                        )
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            steps.forEachIndexed { index, step ->
                                Row(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        text = "${index + 1}. ",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = step,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.settings_oem_why),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(24.dp))

                    // Jump straight to this app's notification settings.
                    Button(
                        onClick = {
                            runCatching {
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                ).apply {
                                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_oem_open_notif_settings))
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_oem_done))
                    }
                }
            }
        )
    }
}

/** Normalized OEM brand (menu paths differ per family). */
private enum class OemBrand { XIAOMI, SAMSUNG, OPPO, VIVO, HUAWEI, GENERIC }

private fun oemBrand(manufacturer: String?): OemBrand {
    val m = manufacturer?.lowercase() ?: return OemBrand.GENERIC
    return when {
        "xiaomi" in m || "redmi" in m || "poco" in m -> OemBrand.XIAOMI
        "samsung" in m -> OemBrand.SAMSUNG
        "oppo" in m || "realme" in m || "oneplus" in m -> OemBrand.OPPO
        "vivo" in m || "iqoo" in m -> OemBrand.VIVO
        "huawei" in m || "honor" in m -> OemBrand.HUAWEI
        else -> OemBrand.GENERIC
    }
}

/** Display name for the detected brand (falls back to the raw manufacturer). */
private fun oemBrandLabel(context: android.content.Context, brand: OemBrand): String = when (brand) {
    OemBrand.XIAOMI -> "Xiaomi / Redmi / POCO"
    OemBrand.SAMSUNG -> "Samsung"
    OemBrand.OPPO -> "OPPO / realme"
    OemBrand.VIVO -> "vivo"
    OemBrand.HUAWEI -> "Huawei / Honor"
    OemBrand.GENERIC -> Build.MANUFACTURER ?: "Android"
}
