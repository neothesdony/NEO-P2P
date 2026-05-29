package com.neop2p.di

import org.koin.core.module.Module
import org.koin.dsl.module
import com.neop2p.state.HomeViewModel
import com.neop2p.data.repository.OfferRepository
import com.neop2p.data.repository.OfferRepositoryImpl
import com.neop2p.data.remote.OfferRemoteDataSource
import com.neop2p.state.OnboardingViewModel
import com.neop2p.domain.repository.IdentityRepository
import com.neop2p.data.repository.IdentityRepositoryImpl

val sharedModule = module {
    // Networking
    single { OfferRemoteDataSource() }

    // Repository
    single<OfferRepository> { OfferRepositoryImpl(get()) }
    single<IdentityRepository> { IdentityRepositoryImpl(get()) }

    // ViewModels
    viewModel { HomeViewModel(get()) }
    viewModel { OnboardingViewModel(get()) }
}