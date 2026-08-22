package com.neop2p

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.navigation.NeoP2PNavGraph
import com.neop2p.navigation.Routes
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var identityManager: IdentityManager

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeoP2PTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NeoP2PNavGraph(
                        startDestination = if (identityManager.hasIdentity()) {
                            Routes.HOME
                        } else {
                            Routes.ONBOARDING
                        }
                    )
                }
            }
        }
    }
}
