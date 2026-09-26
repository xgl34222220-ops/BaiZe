package io.github.xgl34222220.baize.root

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded I/O parallelism across apps; each app's cache roots stay sequential. */
internal object CacheCleanQueue {
    fun <T, R> run(
        groups: List<List<T>>,
        workers: Int,
        cancelled: AtomicBoolean,
        process: (T) -> R,
        completed: (T, R) -> Unit
    ) {
        if (groups.isEmpty() || cancelled.get()) return
        val parallelism = workers.coerceIn(1, 4).coerceAtMost(groups.size)
        val executor = Executors.newFixedThreadPool(parallelism)
        val completions = ExecutorCompletionService<List<Pair<T, R>>>(executor)
        var next = 0
        var active = 0
        var interrupted = false
        fun submit() {
            while (active < parallelism && next < groups.size && !cancelled.get()) {
                val group = groups[next++]
                completions.submit(Callable {
                    val results = ArrayList<Pair<T, R>>()
                    for (item in group) {
                        if (cancelled.get()) break
                        results += item to process(item)
                    }
                    results
                })
                active++
            }
        }
        try {
            submit()
            // Even after cancellation, collect every in-flight mutation before reporting.
            while (active > 0) {
                val future = try {
                    completions.take()
                } catch (_: InterruptedException) {
                    interrupted = true
                    cancelled.set(true)
                    continue
                }
                active--
                for ((item, result) in future.get()) completed(item, result)
                submit()
            }
        } catch (error: Throwable) {
            cancelled.set(true)
            throw error
        } finally {
            executor.shutdown()
            // Do not return while a worker can still delete files or observe a reset cancel flag.
            while (!executor.isTerminated) {
                try {
                    executor.awaitTermination(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                    cancelled.set(true)
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
