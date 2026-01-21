package com.example.meshvisualiser.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme =
        darkColorScheme(
                primary = Primary,
                secondary = Secondary,
                tertiary = SecondaryVariant,
                background = Background,
                surface = Surface,
                error = Error,
                onPrimary = OnPrimary,
                onSecondary = OnSecondary,
                onBackground = OnBackground,
                onSurface = OnSurface,
                onError = OnError
        )

private val LightColorScheme =
        lightColorScheme(primary = Primary, secondary = Secondary, tertiary = SecondaryVariant)

@Composable
fun MeshVisualiserTheme(
        darkTheme: Boolean = isSystemInDarkTheme(),
        content: @Composable () -> Unit
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as Activity).window
      window.statusBarColor = android.graphics.Color.TRANSPARENT
      WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
    }
  }

  MaterialTheme(colorScheme = colorScheme, content = content)
}
