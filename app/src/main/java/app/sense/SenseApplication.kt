package app.sense

import android.app.Application
import app.sense.audio.*
import app.sense.camera.*
import app.sense.core.*
import app.sense.data.*
import app.sense.packs.*
import app.sense.radar.*
import kotlinx.coroutines.*

class SenseApplication : Application() {
    lateinit var graph: SenseGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = SenseGraph(this)
    }
}

class SenseGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val capabilities = CapabilityService(app)
    val database = SenseDatabase.create(app)
    val store = LocalStore(app, database.records(), SecureVault(app))
    val backend = Backend(store)
    val radar = RadarService(app, capabilities, backend, scope)
    val hear = HearEngine(app, capabilities, scope)
    val whisper = WhisperService(app, capabilities, backend, radar, hear, scope)
    val camera = CameraPipeline(app, scope)
    val silent = SilentService(camera, store, scope)
    val moment = MomentService(camera, backend, store, scope)
    val packs = PackManager(app, database.records(), capabilities, scope)
}
