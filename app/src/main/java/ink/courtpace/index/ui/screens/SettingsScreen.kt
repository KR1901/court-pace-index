package ink.courtpace.index.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.courtpace.index.data.AppSettings
import ink.courtpace.index.data.ThemeMode
import ink.courtpace.index.ui.glass.GlassButton
import ink.courtpace.index.ui.glass.GlassChip
import ink.courtpace.index.ui.glass.GlassNote
import ink.courtpace.index.ui.glass.GlassSurface
import ink.courtpace.index.ui.glass.HairLine
import ink.courtpace.index.ui.glass.SectionLabel
import ink.courtpace.index.ui.theme.PaceTheme

@Composable
fun SettingsScreen(
    settings: AppSettings,
    courtCount: Int,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onExport: () -> String,
    onImport: (String) -> Unit,
) {
    val colors = PaceTheme.colors
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val payload = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (payload != null) onImport(payload)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text(
            text = "SETTINGS",
            style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp),
            color = colors.textFaint,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Defaults and data",
            style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.1).sp),
            color = colors.text,
        )

        Spacer(Modifier.height(20.dp))
        SectionLabel("Appearance")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                GlassChip(
                    text = mode.label,
                    selected = settings.theme == mode,
                    onClick = { onUpdate { it.copy(theme = mode) } },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Measurement defaults")
        Spacer(Modifier.height(10.dp))
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(18.dp),
        ) {
            Text(
                text = "Each new test starts from these. They do not change saved courts.",
                style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
                color = colors.textDim,
            )
            Spacer(Modifier.height(12.dp))
            HairLine()
            Spacer(Modifier.height(12.dp))
            ink.courtpace.index.ui.glass.StatRow(
                "Default drop height",
                "${settings.defaultDropHeightCm.roundInt()} cm",
            )
            ink.courtpace.index.ui.glass.StatRow(
                "Default ball temperature",
                "${settings.defaultTemperatureC.oneDecimal()} °C",
            )
            ink.courtpace.index.ui.glass.StatRow(
                "Default surface",
                settings.defaultSurface.shortLabel,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip(
                    text = "254 cm",
                    selected = settings.defaultDropHeightCm == 254.0,
                    onClick = { onUpdate { it.copy(defaultDropHeightCm = 254.0) } },
                )
                GlassChip(
                    text = "127 cm",
                    selected = settings.defaultDropHeightCm == 127.0,
                    onClick = { onUpdate { it.copy(defaultDropHeightCm = 127.0) } },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Your data")
        Spacer(Modifier.height(10.dp))
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(18.dp),
        ) {
            Text(
                text = "$courtCount court${if (courtCount == 1) "" else "s"} stored on this device. " +
                    "Nothing is uploaded anywhere.",
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                color = colors.textDim,
            )
            Spacer(Modifier.height(14.dp))
            GlassButton(
                text = "Export as JSON",
                onClick = {
                    val payload = onExport()
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_TEXT, payload)
                        putExtra(Intent.EXTRA_SUBJECT, "Court Pace export")
                    }
                    context.startActivity(Intent.createChooser(intent, "Export court data"))
                },
                prominent = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            GlassButton(
                text = "Import from JSON",
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                prominent = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("About")
        Spacer(Modifier.height(10.dp))
        GlassNote(
            "Court Pace Index 1.0.0. Protocol CPI-v1. Built on the ITF CS 01/02 equations; " +
                "a predictive estimate, not an ITF classification.",
        )
        Spacer(Modifier.height(130.dp))
    }
}
