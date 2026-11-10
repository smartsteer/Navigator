package eu.smartsteer.navigator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.smartsteer.navigator.core.update.PlatformUpdater
import eu.smartsteer.navigator.core.update.ReleaseInfo
import eu.smartsteer.navigator.core.update.Releases
import eu.smartsteer.navigator.core.update.platformUpdater
import eu.smartsteer.navigator.ui.components.ActionButton
import eu.smartsteer.navigator.ui.components.ButtonText
import eu.smartsteer.navigator.ui.theme.Ss
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data class Failed(val release: ReleaseInfo, val message: String) : UpdateState
}

/** Sprawdzanie nowych wydań na GitHub (przy starcie i co kilka godzin). Nic nie instaluje bez zgody rolnika. */
class UpdateController(val platform: PlatformUpdater = platformUpdater()) {
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    private var dismissedBuild = 0

    suspend fun check() {
        if (state is UpdateState.Downloading) return
        try {
            val r = Releases.parseLatest(platform.fetchLatestJson()) ?: return
            if (Releases.isNewer(r, platform.currentBuild) && r.build != dismissedBuild) state = UpdateState.Available(r)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // brak internetu w polu to normalna sytuacja — nie przeszkadzamy
        }
    }

    fun dismiss() {
        (state as? UpdateState.Available)?.let { dismissedBuild = it.release.build }
        (state as? UpdateState.Failed)?.let { dismissedBuild = it.release.build }
        state = UpdateState.Idle
    }

    suspend fun install(r: ReleaseInfo) {
        state = UpdateState.Downloading(r, 0f)
        try {
            platform.install(r) { state = UpdateState.Downloading(r, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = UpdateState.Failed(r, e.message ?: "nieznany błąd")
        }
    }

    companion object {
        const val FIRST_CHECK_MS = 5_000L
        const val INTERVAL_MS = 4L * 60 * 60 * 1000
    }
}

/** Pasek pod górną belką; widoczny tylko, gdy jest nowa wersja. */
@Composable
fun UpdateBanner(upd: UpdateController, autosteerOn: Boolean) {
    val st = upd.state
    if (st == UpdateState.Idle) return
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val have = upd.platform.currentVersion
    Row(
        Modifier.fillMaxWidth().background(Ss.Panel2).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            when (st) {
                is UpdateState.Available -> {
                    Text("Dostępna aktualizacja ${st.release.version}", color = Ss.Info, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            autosteerOn -> "Masz $have. Wyłącz autoster, żeby zainstalować."
                            upd.platform.canInstall -> "Masz $have. Aplikacja zamknie się na chwilę i uruchomi ponownie."
                            else -> "Masz $have. Pobierz nowy plik APK i zainstaluj."
                        },
                        color = Ss.TextMuted, fontSize = 13.sp,
                    )
                }
                is UpdateState.Downloading -> {
                    Text("Pobieranie aktualizacji ${st.release.version}… ${(st.progress * 100).toInt()}%", color = Ss.Info, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Po pobraniu Windows zapyta o zgodę na instalację.", color = Ss.TextMuted, fontSize = 13.sp)
                }
                is UpdateState.Failed -> {
                    Text("Aktualizacja nie powiodła się", color = Ss.Warning, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(st.message, color = Ss.TextMuted, fontSize = 13.sp)
                }
                UpdateState.Idle -> {}
            }
        }
        val release = (st as? UpdateState.Available)?.release ?: (st as? UpdateState.Failed)?.release
        if (release != null) {
            val label = when {
                st is UpdateState.Failed -> "Spróbuj ponownie"
                upd.platform.canInstall -> "Zainstaluj"
                else -> "Pobierz APK"
            }
            val enabled = !autosteerOn
            ActionButton(
                background = if (enabled) Ss.Accent else Ss.SurfaceHigh,
                border = if (enabled) Ss.Accent else Ss.BorderStrong,
                onClick = {
                    if (!enabled) return@ActionButton
                    if (upd.platform.canInstall) scope.launch { upd.install(release) }
                    else uri.openUri(release.apkUrl ?: release.pageUrl)
                },
            ) { ButtonText(label, color = if (enabled) Ss.OnAccent else Ss.TextMuted, size = 15.sp, weight = FontWeight.Bold) }
            ActionButton(onClick = { upd.dismiss() }) { ButtonText("Później", size = 15.sp) }
        }
    }
}
