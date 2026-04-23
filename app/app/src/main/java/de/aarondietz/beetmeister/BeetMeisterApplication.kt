package de.aarondietz.beetmeister

import android.app.Application
import de.aarondietz.beetmeister.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.logger.AndroidLogger
import org.koin.core.context.startKoin

class BeetMeisterApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            logger(AndroidLogger())
            androidContext(this@BeetMeisterApplication)
            modules(appModule)
        }
    }
}
