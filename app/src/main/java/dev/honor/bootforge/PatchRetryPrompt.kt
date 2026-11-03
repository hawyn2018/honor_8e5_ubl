package dev.honor.bootforge

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.honor.bootforge.core.PatchRetryBroker
import dev.honor.bootforge.core.PatchRetryTask
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text

/** Services wait only on IO; the active request belongs to the process, not an Activity. */
internal object PatchRetryPrompt {
    private val broker = PatchRetryBroker()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    var pending by mutableStateOf<PatchRetryBroker.Request?>(null)
        private set
    val waiting: Boolean get() = broker.pending() != null

    fun awaitChoice(task: PatchRetryTask, error: Exception): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "镜像修补确认必须在后台任务中等待" }
        val ticket = broker.open(task, error)
        publish()
        return try {
            ticket.await()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } finally {
            // An answer/cancel may already have opened a different request.
            // Its unique id must never be cleared by this completed waiter.
            broker.cancelRequest(ticket.request.id)
            publish()
        }
    }

    fun respond(id: Long, useBuiltin: Boolean) {
        broker.respond(id, useBuiltin)
        publish()
    }

    fun cancel(task: PatchRetryTask) {
        broker.cancel(task)
        publish()
    }

    private fun publish() {
        if (Looper.myLooper() == Looper.getMainLooper()) pending = broker.pending()
        else main.post { pending = broker.pending() }
    }
}

@Composable
internal fun PatchRetryDialog() {
    val request = PatchRetryPrompt.pending ?: return
    Dialog(onDismissRequest = { PatchRetryPrompt.respond(request.id, false) },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false,
            usePlatformDefaultWidth = false)) {
        Card(modifier = Modifier.padding(22.dp).widthIn(max = 480.dp).fillMaxWidth(),
            cornerRadius = 22.dp, insideMargin = PaddingValues(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("修补失败", fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                Text(request.task.confirmationMessage,
                    modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                    fontSize = 15.sp, lineHeight = 24.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { PatchRetryPrompt.respond(request.id, false) },
                        modifier = Modifier.weight(1f), cornerRadius = 16.dp) { Text("否") }
                    Button(onClick = { PatchRetryPrompt.respond(request.id, true) },
                        modifier = Modifier.weight(1f), cornerRadius = 16.dp,
                        colors = ButtonDefaults.buttonColorsPrimary()) { Text("是", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}
