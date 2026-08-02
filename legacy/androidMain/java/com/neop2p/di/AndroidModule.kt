package com.neop2p.di

import org.koin.android.ext.androi
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module
import android.app.Application
import com.neop2p.shared.di.SharedModule

class NeoP2PApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@NeoP2PApplication)
            modules(sharedModule, androidModule)
        }
    }
}

val androidModule = module {
    // Android-specific ViewModels (if any) can be defined here
    // For now, we are using the shared ViewModel
    viewModel { shared.get() }
}