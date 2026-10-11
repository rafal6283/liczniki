package pl.kreska.app

import android.app.Application

class KreskaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Notifier.createChannels(this)
        Alarms.scheduleAll(this)
        Notifier.refresh(this)
    }
}
