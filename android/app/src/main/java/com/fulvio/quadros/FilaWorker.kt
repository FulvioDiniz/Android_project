package com.fulvio.quadros

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class FilaWorker(
    contexto: Context,
    params: WorkerParameters,
) : CoroutineWorker(contexto, params) {
    override suspend fun doWork(): Result {
        val dao = banco(applicationContext).dao()
        if (dao.ler("token").isNullOrBlank()) return Result.success()
        val enviou = drenarFila(dao)
        val ainda = dao.pendentes().isNotEmpty()
        return when {
            !enviou || ainda -> Result.retry()
            else -> Result.success()
        }
    }
}

fun agendarFila(contexto: Context) {
    val pedido = OneTimeWorkRequestBuilder<FilaWorker>()
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(contexto).enqueueUniqueWork(
        "fila-quadros",
        ExistingWorkPolicy.KEEP,
        pedido,
    )
}
