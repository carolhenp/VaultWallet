package com.micro.airwallet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.micro.airprotocol.WireCodec
import com.micro.airprotocol.WireMessage
import java.util.Base64

data class WatchedAccount(val address: String, val publicKey: String = "")
data class HistoryEntry(val hash: String, val amount: String, val direction: String, val time: Long)

class WatchStore(
    context: Context,
    prefsName: String = "watch_only",
    historyDbName: String = "watch_history.db"
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val historyDb = HistoryDatabase(context.applicationContext, historyDbName)

    @Synchronized
    fun migrateLegacyHistory() {
        val knownAccounts = accounts()
        WalletChain.entries.forEach { chain ->
            val address = knownAccounts[chain]?.address ?: return@forEach
            val legacyHistory = prefs.getString("history_${chain.name}", null)
            val legacyBalance = prefs.getString("balance_${chain.name}", null)
            if (legacyHistory != null) {
                val rows = JSONArray(legacyHistory)
                historyDb.upsert(chain, address, (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    HistoryEntry(row.getString("hash"), row.optString("amount"),
                        row.optString("direction"), row.optLong("time"))
                })
            }
            prefs.edit().apply {
                if (legacyHistory != null) remove("history_${chain.name}")
                if (legacyBalance != null) {
                    putString(balanceKey(chain, address), legacyBalance)
                    remove("balance_${chain.name}")
                }
            }.apply()
        }
    }

    fun accounts(): Map<WalletChain, WatchedAccount> {
        val json = JSONObject(prefs.getString("accounts", "{}") ?: "{}")
        return WalletChain.entries.mapNotNull { chain ->
            val item = json.optJSONObject(chain.name) ?: return@mapNotNull null
            chain to WatchedAccount(item.optString("address"), item.optString("publicKey"))
        }.toMap()
    }

    @Synchronized
    fun saveAccounts(accounts: Map<WalletChain, WatchedAccount>) {
        migrateLegacyHistory()
        val json = JSONObject()
        accounts.forEach { (chain, account) ->
            json.put(chain.name, JSONObject().put("address", account.address).put("publicKey", account.publicKey))
        }
        prefs.edit().putString("accounts", json.toString()).apply()
    }

    fun endpoint(chain: WalletChain) = prefs.getString("endpoint_${chain.name}", chain.endpoint) ?: chain.endpoint

    fun saveEndpoint(chain: WalletChain, endpoint: String) {
        require(endpoint.startsWith("https://")) { "节点地址必须使用 HTTPS" }
        prefs.edit().putString("endpoint_${chain.name}", endpoint.trimEnd('/')).apply()
    }

    fun balance(chain: WalletChain, address: String) = prefs.getString(balanceKey(chain, address), "--") ?: "--"
    fun saveBalance(chain: WalletChain, address: String, amount: String) {
        prefs.edit().putString(balanceKey(chain, address), amount).apply()
    }

    fun lastSync(chain: WalletChain, address: String): Long =
        prefs.getLong("sync_${balanceKey(chain, address)}", 0)

    fun saveLastSync(chain: WalletChain, address: String, timestamp: Long) {
        prefs.edit().putLong("sync_${balanceKey(chain, address)}", timestamp).apply()
    }

    fun history(chain: WalletChain, address: String): List<HistoryEntry> = historyDb.history(chain, address)

    fun saveHistory(chain: WalletChain, address: String, entries: List<HistoryEntry>) =
        historyDb.upsert(chain, address, entries)

    fun close() = historyDb.close()

    private fun balanceKey(chain: WalletChain, address: String) =
        "balance_${chain.name}_${if (chain.isEvm) address.lowercase(java.util.Locale.ROOT) else address}"

    fun pending(): WireMessage? = prefs.getString("pending", null)?.let {
        runCatching { WireCodec.decode(Base64.getDecoder().decode(it)) }.getOrNull()
    }

    fun savePending(message: WireMessage?) {
        prefs.edit().apply {
            if (message == null) remove("pending")
            else putString("pending", Base64.getEncoder().encodeToString(WireCodec.encode(message)))
        }.apply()
    }
}
