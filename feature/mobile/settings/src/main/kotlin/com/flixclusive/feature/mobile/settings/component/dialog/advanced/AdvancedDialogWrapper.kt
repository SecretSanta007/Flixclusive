package com.flixclusive.feature.mobile.settings.component.dialog.advanced

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.flixclusive.core.ui.common.util.showToast
import com.flixclusive.core.util.common.ui.UiText
import com.flixclusive.feature.mobile.settings.KEY_DOH_DIALOG
import com.flixclusive.feature.mobile.settings.component.dialog.advanced.dialog.AdvancedDialogDoH
import com.flixclusive.feature.mobile.settings.util.AppSettingsHelper.rememberLocalAppSettings
import com.flixclusive.feature.mobile.settings.util.AppSettingsHelper.rememberAppSettingsChanger
import com.flixclusive.core.util.R as UtilR

@Composable
internal fun AdvancedDialogWrapper(
    openedDialogMap: Map<String, Boolean>,
    onDismissDialog: (String) -> Unit
) {
    val context = LocalContext.current
    val appSettings by rememberLocalAppSettings()
    val onChangeSettings by rememberAppSettingsChanger()

    when {
        openedDialogMap[KEY_DOH_DIALOG] == true -> {
            AdvancedDialogDoH(
                appSettings = appSettings,
                onChange = {
                    onChangeSettings(appSettings.copy(dns = it))
                    onDismissDialog(KEY_DOH_DIALOG)
                    context.showToast(UiText.StringResource(UtilR.string.restart_app_for_changes_message).asString(context))
                },
                onDismissRequest = {
                    onDismissDialog(KEY_DOH_DIALOG)
                }
            )
        }
    }
}