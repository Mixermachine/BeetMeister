package de.aarondietz.beetmeister.di

import de.aarondietz.beetmeister.beet.BeetRepository
import de.aarondietz.beetmeister.ui.BeetAppViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    factory { BeetRepository(androidContext()) }
    viewModel { BeetAppViewModel(get()) }
}
