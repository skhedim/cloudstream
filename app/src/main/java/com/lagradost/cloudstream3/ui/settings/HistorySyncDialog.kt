package com.lagradost.cloudstream3.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream4.compose.BlackButton
import com.lagradost.cloudstream4.compose.WhiteButton
import com.lagradost.cloudstream4.sync.GitHubLogin
import com.lagradost.cloudstream4.sync.HistorySyncService
import com.lagradost.cloudstream4.sync.SyncProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** The sync's state as a settings subtitle. */
@Composable
fun historySyncSummary(): String {
    val status by HistorySyncService.status.collectAsState()
    val account = status.account
    return when {
        !status.available -> stringResource(R.string.sync_unavailable)
        account == null -> stringResource(R.string.sync_summary_off)
        else -> status.lastSync?.let {
            stringResource(R.string.sync_summary_on_time, account.repository, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)))
        } ?: stringResource(R.string.sync_summary_on, account.repository)
    }
}

/**
 * Connects this device to the shared history with GitHub's device flow: the TV shows a code, the user
 * enters it on a phone or computer. Once connected: sync now, or disconnect.
 */
@Composable
fun HistorySyncDialog(dismiss: () -> Unit) {
    val status by HistorySyncService.status.collectAsState()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<GitHubLogin.DeviceCode?>(null) }
    var problem by remember { mutableStateOf<SyncProblem?>(null) }
    var connecting by remember { mutableStateOf<Job?>(null) }
    val account = status.account
    val shown = code
    val close = { connecting?.cancel(); dismiss() }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.background,
        onDismissRequest = close,
        title = { Text(text = stringResource(R.string.sync_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    account != null -> Text(text = historySyncSummary())
                    shown != null -> {
                        Text(text = stringResource(R.string.sync_code, shown.verificationUri))
                        Text(text = shown.userCode, style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                        Text(text = stringResource(R.string.sync_waiting))
                    }
                    else -> Text(text = stringResource(R.string.sync_description))
                }
                (problem ?: status.problem)?.let {
                    Text(text = stringResource(problemText(it)), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when {
                !status.available -> Unit
                account != null -> WhiteButton(text = stringResource(if (status.busy) R.string.sync_running else R.string.sync_now),
                    onClick = { scope.launch(Dispatchers.IO) { HistorySyncService.sync() } })
                shown == null -> WhiteButton(text = stringResource(R.string.sync_connect), onClick = {
                    connecting = scope.launch {
                        problem = null
                        val started = runCatching { HistorySyncService.startConnect() }.getOrElse {
                            problem = SyncProblem.Network
                            return@launch
                        }
                        code = started
                        try { problem = HistorySyncService.finishConnect(started) } finally { code = null }
                    }
                })
            }
        },
        dismissButton = {
            if (account != null) BlackButton(text = stringResource(R.string.sync_disconnect), onClick = HistorySyncService::disconnect)
            else BlackButton(text = stringResource(R.string.cancel), onClick = close)
        },
    )
}

private fun problemText(problem: SyncProblem) = when (problem) {
    SyncProblem.Unauthorized -> R.string.sync_problem_unauthorized
    SyncProblem.NoRepository -> R.string.sync_problem_no_repository
    SyncProblem.LoginDenied -> R.string.sync_problem_denied
    SyncProblem.Network -> R.string.sync_problem_network
}
