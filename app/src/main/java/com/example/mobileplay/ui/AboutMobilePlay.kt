package com.example.mobileplay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PROJECT_URL = "https://github.com/meek72911/Ps5-mobile"

private data class AboutDocument(val title: String, val asset: String)

private val privacy = AboutDocument("Privacy", "privacy.txt")
private val notices = listOf(
    AboutDocument("MobilePlay and upstream credits", "notices.txt"),
    AboutDocument("GNU Affero General Public License v3", "mobileplay-agpl.txt"),
    AboutDocument("Chiaki and OpenSSL linking permission", "chiaki-agpl-openssl.txt"),
    AboutDocument("OpenSSL 3.5.9", "openssl.txt"),
    AboutDocument("Opus 1.5.2", "opus.txt"),
    AboutDocument("curl", "curl.txt"),
    AboutDocument("json-c", "json-c.txt"),
    AboutDocument("miniupnpc", "miniupnpc.txt"),
    AboutDocument("nanopb", "nanopb.txt"),
    AboutDocument("Jerasure", "jerasure.txt"),
    AboutDocument("GF-Complete", "gf-complete.txt")
)

@Composable
internal fun AboutMobilePlay(onClose: () -> Unit) {
    var showNotices by remember { mutableStateOf(false) }
    var document by remember { mutableStateOf<AboutDocument?>(null) }
    val uriHandler = LocalUriHandler.current
    val goBack = {
        when {
            document != null -> document = null
            showNotices -> showNotices = false
            else -> onClose()
        }
    }
    AlertDialog(
        onDismissRequest = goBack,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (document == null && !showNotices) {
                    Image(painterResource(R.drawable.mobileplay_mark), null, Modifier.size(44.dp))
                }
                Text(document?.title ?: if (showNotices) "Open-source licenses" else "About MobilePlay")
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val selectedDocument = document
                when {
                    selectedDocument != null -> AboutDocumentText(selectedDocument)
                    showNotices -> notices.forEach { notice ->
                        TextButton(onClick = { document = notice }) { Text(notice.title) }
                    }
                    else -> {
                        Text("Developed by Sarthak Khedkar", style = MaterialTheme.typography.titleMedium)
                        Text("Version ${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}")
                        Text("© 2026 Sarthak Khedkar · Original MobilePlay contributions")
                        Text("Independent Remote Play client. MobilePlay is not affiliated with Sony Interactive Entertainment.")
                        TextButton(onClick = { document = privacy }) { Text("Privacy") }
                        TextButton(onClick = { showNotices = true }) { Text("Open-source licenses") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { uriHandler.openUri(PROJECT_URL) }) { Text("Project source") }
                            TextButton(onClick = { uriHandler.openUri("$PROJECT_URL/issues") }) { Text("Support") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = goBack) { Text(if (document != null || showNotices) "Back" else "Close") }
        }
    )
}

@Composable
private fun AboutDocumentText(document: AboutDocument) {
    val assets = LocalContext.current.assets
    val contents by produceState<String?>(null, document) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                assets.open("about/${document.asset}").bufferedReader().use { it.readText() }
            }.getOrDefault("This document is unavailable in this build.")
        }
    }
    SelectionContainer(Modifier.padding(vertical = 4.dp)) {
        Text(contents ?: "Loading…", style = MaterialTheme.typography.bodySmall)
    }
}
