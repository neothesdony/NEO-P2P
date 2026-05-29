package com.neop2p.di

import org.koin.core.module.Module
import org.koin.dsl.module
import com.neop2p.state.HomeViewModel
import com.neop2p.data.repository.OfferRepository
import com.neop2p.data.repository.OfferRepositoryImpl
import com.neop2p.data.remote.OfferRemoteDataSource

val sharedModule = module {
    // Networking
    single { OfferRemoteDataSource() }

    // Repository
    single<OfferRepository> { OfferRepositoryImpl(get()) }

    // ViewModels
    viewModel { HomeViewModel(get()) }
}