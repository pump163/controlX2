package com.jwoglom.controlx2.presentation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R

/**
 * Shown when `FROM_PUMP_MISSING_PAIRING_CODE` arrives but the pump uses a 16-character
 * pairing code — impractical to enter on a watch. Instructs the user to pair via
 * the phone and then switch the watch to pump-host in settings.
 */
@Composable
fun PairingUnsupportedOnWatchScreen() {
    FullScreenText(
        text = stringResource(R.string.setup_16_char_code_pair_on_phone_first),
    )
}
