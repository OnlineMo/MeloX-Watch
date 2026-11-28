package io.github.onlinemo.meloxwatch.ui.library

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.provider.MediaStore
import org.robolectric.Shadows.shadowOf

/** Actual PackageManager registrations; read permission and ContentProvider bytes remain separate. */
internal fun installSourcesPicker(context: Context, kind: SourcesPickerKind) {
    val component = sourcesPickerComponent(kind)
    val handler = ActivityInfo().apply {
        packageName = component.packageName
        name = component.className
        enabled = true
        exported = true
        applicationInfo = ApplicationInfo().apply { packageName = component.packageName; enabled = true }
    }
    val packages = shadowOf(context.packageManager)
    packages.addOrUpdateActivity(handler)
    // Replacing this handler keeps one filter without changing the other picker component.
    packages.clearIntentFilterForActivity(component)
    val filter = IntentFilter(if (kind == SourcesPickerKind.TREE) Intent.ACTION_OPEN_DOCUMENT_TREE else Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        if (kind == SourcesPickerKind.FILE) {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("audio/*")
        }
    }
    packages.addIntentFilterForActivity(component, filter)
}

internal fun removeSourcesPicker(context: Context, kind: SourcesPickerKind) {
    shadowOf(context.packageManager).removeActivity(sourcesPickerComponent(kind))
}

private fun sourcesPickerComponent(kind: SourcesPickerKind): ComponentName {
    require(!kind.isMedia)
    return ComponentName(SOURCES_PICKER_PACKAGE, "$SOURCES_PICKER_PACKAGE.${kind.name}")
}

internal fun installSourcesMediaPresence(context: Context, enabled: Boolean = true, exported: Boolean = true,
    packageName: String = context.packageName) {
    val provider = ProviderInfo().apply {
        authority = MediaStore.AUTHORITY
        this.packageName = packageName
        name = "$packageName.SourcesMediaProvider"
        this.enabled = enabled
        this.exported = exported
        applicationInfo = ApplicationInfo().apply { this.packageName = packageName; this.enabled = true }
    }
    shadowOf(context.packageManager).addOrUpdateProvider(provider)
}

private const val SOURCES_PICKER_PACKAGE = "sources.fixture.picker"
