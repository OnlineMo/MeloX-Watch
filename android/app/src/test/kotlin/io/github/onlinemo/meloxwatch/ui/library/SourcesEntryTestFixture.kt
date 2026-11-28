package io.github.onlinemo.meloxwatch.ui.library

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.provider.MediaStore
import org.robolectric.Shadows.shadowOf

/** Actual PackageManager registrations; read permission and ContentProvider bytes remain separate. */
internal fun installSourcesPicker(context: Context, kind: SourcesPickerKind) {
    require(!kind.isMedia)
    val handler = ResolveInfo().apply {
        isDefault = true
        activityInfo = ActivityInfo().apply {
            packageName = SOURCES_PICKER_PACKAGE
            name = "$SOURCES_PICKER_PACKAGE.${kind.name}"
            enabled = true
            exported = true
            applicationInfo = ApplicationInfo().apply { packageName = SOURCES_PICKER_PACKAGE; enabled = true }
        }
    }
    shadowOf(context.packageManager).addResolveInfoForIntent(sourcesPickerIntent(kind), handler)
}

internal fun removeSourcesPicker(context: Context, kind: SourcesPickerKind) {
    shadowOf(context.packageManager).removeResolveInfosForIntent(sourcesPickerIntent(kind), SOURCES_PICKER_PACKAGE)
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
