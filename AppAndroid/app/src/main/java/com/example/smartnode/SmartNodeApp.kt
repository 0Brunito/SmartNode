package com.example.smartnode

import android.app.Application
import com.example.smartnode.data.SmartNodeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SmartNodeApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val repository: SmartNodeRepository by lazy { SmartNodeRepository(this, appScope) }

    override fun onCreate() {
        super.onCreate()
        repository.connect()
    }
}
