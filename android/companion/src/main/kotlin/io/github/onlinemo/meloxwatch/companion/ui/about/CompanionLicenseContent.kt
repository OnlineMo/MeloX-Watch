package io.github.onlinemo.meloxwatch.companion.ui.about

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.res.stringResource
import io.github.onlinemo.meloxwatch.companion.R
import io.github.onlinemo.meloxwatch.companion.ui.MeloXAction
import io.github.onlinemo.meloxwatch.companion.ui.MeloXCard
import io.github.onlinemo.meloxwatch.companion.ui.MeloXText

internal fun LazyListScope.companionLicenseItems(state: CompanionLicensesState, model: CompanionLicensesViewModel) {
    val selected = state.selected
    if (selected == null) {
        item(key = "licenses-introduction") { MeloXText(stringResource(R.string.licenses_offline), secondary = true) }
        items(state.entries, key = { "license-${it.id}" }) { entry ->
            MeloXCard {
                MeloXAction(stringResource(R.string.license_entry, entry.name, entry.version), { model.open(entry.id) })
                MeloXText(entry.license, secondary = true)
            }
        }
    } else {
        item(key = "license-title") {
            MeloXCard {
                MeloXText(selected.name, heading = true)
                MeloXText(stringResource(R.string.license_details, selected.version, selected.license), secondary = true)
                MeloXAction(stringResource(R.string.back_to_licenses), { model.backToList() })
            }
        }
        itemsIndexed(state.sections, key = { index, _ -> "license-text-$index" }) { _, section ->
            MeloXText(section)
        }
    }
    if (state.loading) item(key = "license-loading") {
        MeloXCard {
            MeloXText(stringResource(R.string.licenses_loading), secondary = true)
            MeloXAction(stringResource(R.string.cancel), model::cancel)
        }
    }
    if (state.unavailable || state.cancelled) item(key = "license-error") {
        MeloXCard {
            MeloXText(stringResource(if (state.cancelled) R.string.licenses_cancelled else R.string.licenses_unavailable))
            MeloXAction(stringResource(R.string.licenses_retry), model::retry)
        }
    }
}
