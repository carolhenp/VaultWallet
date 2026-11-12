package com.micro.airwallet

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

internal class ChainSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val chain = inputData.getString("chain")?.let { name ->
            WalletChain.entries.firstOrNull { it.name == name }
        } ?: return@withContext Result.failure()
        val address = inputData.getString("address")?.takeIf(String::isNotBlank)
            ?: return@withContext Result.failure()
        val store = WatchStore(applicationContext)
        try {
            store.migrateLegacyHistory()
            val snapshot = ChainClient(store).refresh(chain, address)
            store.saveHistory(chain, address, snapshot.history)
            store.saveBalance(chain, address, snapshot.balance)
            store.saveLastSync(chain, address, System.currentTimeMillis())
            Result.success()
        } catch (exception: IOException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure(errorData(exception))
        } catch (exception: Exception) {
            Result.failure(errorData(exception))
        } finally {
            store.close()
        }
    }

    private fun errorData(exception: Exception) = Data.Builder()
        .putString("error", (exception.message ?: "链上更新失败").take(400)).build()
}
