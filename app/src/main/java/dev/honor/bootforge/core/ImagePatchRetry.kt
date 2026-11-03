package dev.honor.bootforge.core

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

enum class PatchRetryTask(val label: String) {
    UNLOCK("解锁"), OTA("OTA");

    val confirmationMessage: String get() =
        "修补选定镜像失败，是否使用应用内置镜像修补，并继续完成后续${label}流程？"
    val declinedMessage: String get() = "选定镜像修补失败，用户选择停止${label}流程"
}

class PatchRetryDeclinedException(val task: PatchRetryTask, cause: Exception) :
    IOException(task.declinedMessage, cause)

/** Only the caller's patch operation belongs inside primary/builtin, never a write transaction. */
object ImagePatchRetry {
    data class Outcome<T>(val value: T, val usedBuiltin: Boolean)

    fun <T> run(
        task: PatchRetryTask,
        eligible: Boolean,
        primary: () -> T,
        builtin: () -> T,
        confirm: (Exception) -> Boolean,
    ): Outcome<T> {
        val failure = try {
            return Outcome(primary(), usedBuiltin = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (error: Exception) {
            if (!eligible) throw error
            error
        }
        if (!confirm(failure)) throw PatchRetryDeclinedException(task, failure)
        // This attempt is outside the catch above: an inner-image failure must
        // propagate once, without another prompt or transaction restart.
        return Outcome(builtin(), usedBuiltin = true)
    }
}

/** Process-local handoff survives Activity recreation and rejects stale/duplicate answers. */
internal class PatchRetryBroker {
    data class Request(val id: Long, val task: PatchRetryTask, val failureMessage: String)

    class Ticket internal constructor(val request: Request) {
        private val answer = CompletableFuture<Boolean>()

        fun await(): Boolean = try {
            answer.get()
        } catch (failure: ExecutionException) {
            when (val cause = failure.cause) {
                is Exception -> throw cause
                is Error -> throw cause
                else -> throw failure
            }
        }

        internal fun respond(useBuiltin: Boolean) { answer.complete(useBuiltin) }
        internal fun cancel() {
            answer.completeExceptionally(CancellationException("镜像修补确认已取消，流程已停止"))
        }
    }

    private var nextId = 0L
    private var active: Ticket? = null

    @Synchronized
    fun open(task: PatchRetryTask, failure: Exception): Ticket {
        check(active == null) { "已有镜像修补确认正在等待处理" }
        return Ticket(Request(++nextId, task, failure.message ?: failure.javaClass.simpleName))
            .also { active = it }
    }

    @Synchronized
    fun pending(): Request? = active?.request

    @Synchronized
    fun respond(id: Long, useBuiltin: Boolean): Boolean {
        val ticket = active?.takeIf { it.request.id == id } ?: return false
        active = null
        ticket.respond(useBuiltin)
        return true
    }

    @Synchronized
    fun cancel(task: PatchRetryTask): Boolean {
        val ticket = active?.takeIf { it.request.task == task } ?: return false
        active = null
        ticket.cancel()
        return true
    }

    @Synchronized
    fun cancelRequest(id: Long): Boolean {
        val ticket = active?.takeIf { it.request.id == id } ?: return false
        active = null
        ticket.cancel()
        return true
    }
}
