package org.nomadpocket.app

import android.app.Application
import org.maplibre.android.MapLibre
import org.nomadpocket.app.map.KnownRadioStore
import org.nomadpocket.mesh.Mesh

class NomadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // No API key: the map style and tiles come from OpenFreeMap.
        MapLibre.getInstance(this)
        Mesh.init(this)
        KnownRadioStore.init(this)
    }
}
