/*
 * Copyright (C) 2024 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby.preference

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import co.aospa.dolby.R
import com.android.settingslib.spa.framework.theme.SettingsTheme

// Preference with a Material3 slider, matching the graphic equalizer UI.
class DolbySliderPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    private var currentValue = 0

    var value: Int
        get() = currentValue
        set(newValue) = setValueInternal(newValue)

    init {
        layoutResource = R.layout.dolby_slider_preference
        isSelectable = false
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        val default =
            when (defaultValue) {
                is Int -> defaultValue
                is String -> defaultValue.toIntOrNull() ?: MIN
                else -> MIN
            }
        currentValue = getPersistedInt(default).coerceIn(MIN, MAX)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val composeView = holder.findViewById(R.id.dolby_slider_compose) as? ComposeView ?: return
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.Default)
        val enabled = isEnabled
        composeView.setContent {
            SettingsTheme {
                var sliderValue by remember(currentValue) { mutableIntStateOf(currentValue) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = sliderValue.toFloat(),
                        onValueChange = { sliderValue = it.toInt().coerceIn(MIN, MAX) },
                        onValueChangeFinished = { commit(sliderValue) },
                        valueRange = MIN.toFloat()..MAX.toFloat(),
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = sliderValue.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
        }
    }

    private fun commit(newValue: Int) {
        val clamped = newValue.coerceIn(MIN, MAX)
        if (callChangeListener(clamped)) {
            currentValue = clamped
            persistInt(clamped)
        } else {
            notifyChanged()
        }
    }

    private fun setValueInternal(newValue: Int) {
        val clamped = newValue.coerceIn(MIN, MAX)
        if (clamped == currentValue) return
        currentValue = clamped
        persistInt(clamped)
        notifyChanged()
    }

    companion object {
        private const val MIN = 0
        private const val MAX = 100
    }
}
