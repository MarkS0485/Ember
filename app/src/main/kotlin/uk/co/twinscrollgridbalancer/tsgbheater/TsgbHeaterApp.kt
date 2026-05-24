package uk.co.twinscrollgridbalancer.tsgbheater

import android.app.Application
import uk.co.twinscrollgridbalancer.tsgbheater.di.ServiceLocator
import uk.co.twinscrollgridbalancer.tsgbheater.service.HeaterNotification

class TsgbHeaterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        HeaterNotification.ensureChannel(this)
    }
}
