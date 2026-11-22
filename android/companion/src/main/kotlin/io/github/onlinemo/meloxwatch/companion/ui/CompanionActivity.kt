package io.github.onlinemo.meloxwatch.companion.ui

import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.onlinemo.meloxwatch.companion.MeloXCompanionApplication
import io.github.onlinemo.meloxwatch.companion.data.CompanionIconRole
import io.github.onlinemo.meloxwatch.companion.ui.about.CompanionLicensesViewModel

class CompanionActivity : ComponentActivity() {
    private lateinit var model: CompanionViewModel
    private lateinit var licenses: CompanionLicensesViewModel
    private var pendingIcon: IconSelection? = null

    private class IconSelection(val role: CompanionIconRole, val licenseName: String, val licenseText: String) {
        override fun toString(): String = "<local-icon-selection>"
    }

    private val selectAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.prepareAudio(uri)
    }
    private val selectIcon = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val selection = pendingIcon
        pendingIcon = null
        if (uri != null && selection != null) model.prepareIcon(uri, selection.role, selection.licenseName, selection.licenseText)
        else if (uri != null) model.pickerUnavailable()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MeloXCompanionApplication
        val provider = ViewModelProvider(this, viewModelFactory {
            initializer { CompanionViewModel(app.documents, app.transfer) }
            initializer { CompanionLicensesViewModel(app.licenses) }
        })
        model = provider[CompanionViewModel::class.java]
        licenses = provider[CompanionLicensesViewModel::class.java]
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val licenseState by licenses.state.collectAsStateWithLifecycle()
            CompanionUi(state, model, licenseState, licenses, onExit = ::finish, selectAudio = {
                try { selectAudio.launch(arrayOf("audio/*")) }
                catch (_: ActivityNotFoundException) { model.pickerUnavailable() }
                catch (_: SecurityException) { model.pickerUnavailable() }
            }, selectIcon = { role, licenseName, licenseText ->
                pendingIcon = IconSelection(role, licenseName, licenseText)
                try { selectIcon.launch(arrayOf("image/*")) }
                catch (_: ActivityNotFoundException) {
                    pendingIcon = null
                    model.pickerUnavailable()
                }
                catch (_: SecurityException) {
                    pendingIcon = null
                    model.pickerUnavailable()
                }
            })
        }
    }

    override fun onStop() {
        if (::licenses.isInitialized) licenses.background()
        if (::model.isInitialized) model.background()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (::licenses.isInitialized) licenses.foreground()
        if (::model.isInitialized) model.reloadLocal()
    }
}
