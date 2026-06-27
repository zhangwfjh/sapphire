package com.sapphire.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.sapphire.data.work.RetentionScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Hilt entrypoint. All core-data modules use @InstallIn(SingletonComponent::class) so they
 * are auto-discovered; [com.sapphire.app.di.AppConfigModule] contributes the app-local
 * [com.sapphire.data.di.LlmConfigProvider].
 *
 * S07: implements [Configuration.Provider] so WorkManager uses the Hilt-injected
 * [HiltWorkerFactory] (required for @HiltWorker). The default WorkManager initializer is
 * disabled in the manifest; WorkManager initializes on-demand on first use.
 */
@HiltAndroidApp
class SapphireApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var retentionScheduler: RetentionScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Log every uncaught exception to logcat under a recognizable tag. This does NOT
        // swallow the crash — the default handler still runs — but it makes the real
        // root cause visible in `adb logcat | grep SapphireCrash` instead of being buried
        // in a raw Android process-death trace.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("SapphireCrash", "uncaught on ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
        // Idempotent: KEEP policy means a re-launch never duplicates the periodic work.
        retentionScheduler.schedule()
    }
}
