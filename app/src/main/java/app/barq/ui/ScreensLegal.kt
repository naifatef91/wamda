package app.barq.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.barq.R

// ---------- About / Privacy / Terms ----------

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val c = MaterialTheme.colorScheme
    LegalPage(stringResource(R.string.settings_about_app), onBack) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = c.primary)
        Text(stringResource(R.string.about_version), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        LegalSection(R.string.about_desc_title, R.string.about_desc_body)
        LegalSection(R.string.about_never_title, R.string.about_never_body)
        LegalSection(R.string.about_how_title, R.string.about_how_body)
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    LegalPage(stringResource(R.string.settings_privacy), onBack) {
        LegalMeta(stringResource(R.string.legal_updated))
        LegalBody(R.string.privacy_intro)
        LegalSection(R.string.privacy_s1_title, R.string.privacy_s1_body)
        LegalSection(R.string.privacy_s2_title, R.string.privacy_s2_body)
        LegalSection(R.string.privacy_s3_title, R.string.privacy_s3_body)
        LegalSection(R.string.privacy_s4_title, R.string.privacy_s4_body)
        LegalSection(R.string.privacy_s5_title, R.string.privacy_s5_body)
        LegalSection(R.string.privacy_s6_title, R.string.privacy_s6_body)
        LegalSection(R.string.privacy_s7_title, R.string.privacy_s7_body)
        LegalSection(R.string.privacy_s8_title, R.string.privacy_s8_body)
    }
}

@Composable
fun TermsScreen(onBack: () -> Unit) {
    LegalPage(stringResource(R.string.settings_terms), onBack) {
        LegalMeta(stringResource(R.string.legal_updated))
        LegalBody(R.string.terms_intro)
        LegalSection(R.string.terms_s1_title, R.string.terms_s1_body)
        LegalSection(R.string.terms_s2_title, R.string.terms_s2_body)
        LegalSection(R.string.terms_s3_title, R.string.terms_s3_body)
        LegalSection(R.string.terms_s4_title, R.string.terms_s4_body)
        LegalSection(R.string.terms_s5_title, R.string.terms_s5_body)
        LegalSection(R.string.terms_s6_title, R.string.terms_s6_body)
        LegalSection(R.string.terms_s7_title, R.string.terms_s7_body)
        LegalSection(R.string.terms_s8_title, R.string.terms_s8_body)
    }
}

// ---------- Building blocks ----------

/** Same top-bar pattern as HistoryScreen / SettingsScreen, over a scrollable body. */
@Composable
private fun LegalPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar(title, onBack = onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            content = content,
        )
    }
}

@Composable
private fun LegalSection(@StringRes heading: Int, @StringRes body: Int) {
    Spacer(Modifier.height(20.dp))
    Text(stringResource(heading), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(6.dp))
    LegalBody(body)
}

@Composable
private fun LegalBody(@StringRes body: Int) {
    Text(
        stringResource(body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LegalMeta(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
}
