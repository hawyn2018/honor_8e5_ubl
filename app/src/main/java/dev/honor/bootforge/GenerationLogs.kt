package dev.honor.bootforge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keep file generation on IO and ordered UI log updates on Main, including on failure. */
internal suspend fun <T> withGenerationLogs(operation: (log: (String) -> Unit) -> T): T = coroutineScope {
    val messages = Channel<String>(Channel.UNLIMITED)
    val receiver = launch(Dispatchers.Main.immediate) {
        for (message in messages) RunState.append(message)
    }
    try {
        withContext(Dispatchers.IO) {
            operation { message -> messages.trySend(message) }
        }
    } finally {
        messages.close()
        receiver.join()
    }
}
