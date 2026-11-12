package com.micro.airwallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDatabaseTest {
    @Test
    fun upsertPersistsRecordsAndIsolatesAddressesAndChains() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "history_test_${System.nanoTime()}.db"
        val first = HistoryDatabase(context, databaseName)
        val address = "0xABCDEF"
        try {
            first.upsert(WalletChain.ETHEREUM, address, listOf(
                HistoryEntry("old", "1 ETH", "收入", 10),
                HistoryEntry("new", "2 ETH", "支出", 20)
            ))
            first.upsert(WalletChain.ETHEREUM, address.lowercase(), listOf(
                HistoryEntry("new", "3 ETH", "已确认", 20),
                HistoryEntry("latest", "4 ETH", "收入", 30)
            ))
            first.upsert(WalletChain.BASE, address, listOf(HistoryEntry("base", "1 ETH", "收入", 50)))
            assertTrue(first.history(WalletChain.ETHEREUM, "0xOTHER").isEmpty())
            first.close()

            val reopened = HistoryDatabase(context, databaseName)
            try {
                val ethereum = reopened.history(WalletChain.ETHEREUM, address)
                assertEquals(listOf("latest", "new", "old"), ethereum.map(HistoryEntry::hash))
                assertEquals("3 ETH", ethereum[1].amount)
                assertEquals("已确认", ethereum[1].direction)
                assertEquals(listOf("base"), reopened.history(WalletChain.BASE, address).map(HistoryEntry::hash))
            } finally { reopened.close() }
        } finally {
            first.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migratesLegacyPreferencesIntoSqlite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val prefsName = "watch_test_$suffix"
        val databaseName = "watch_test_$suffix.db"
        val prefs = context.getSharedPreferences(prefsName, 0)
        val address = "0x1234567890123456789012345678901234567890"
        prefs.edit()
            .putString("accounts", JSONObject().put(WalletChain.ETHEREUM.name,
                JSONObject().put("address", address).put("publicKey", "")).toString())
            .putString("history_ETHEREUM", JSONArray().put(JSONObject()
                .put("hash", "legacy-hash").put("amount", "0.5 ETH")
                .put("direction", "收入").put("time", 123L)).toString())
            .putString("balance_ETHEREUM", "0.5")
            .commit()
        try {
            WatchStore(context, prefsName, databaseName).useStore { store ->
                store.migrateLegacyHistory()
                assertEquals("0.5", store.balance(WalletChain.ETHEREUM, address))
                assertEquals("legacy-hash", store.history(WalletChain.ETHEREUM, address).single().hash)
                assertFalse(prefs.contains("history_ETHEREUM"))
                assertFalse(prefs.contains("balance_ETHEREUM"))
            }
            WatchStore(context, prefsName, databaseName).useStore { store ->
                assertEquals("legacy-hash", store.history(WalletChain.ETHEREUM, address).single().hash)
                val replacement = "0x9999999999999999999999999999999999999999"
                store.saveAccounts(mapOf(WalletChain.ETHEREUM to WatchedAccount(replacement)))
                assertTrue(store.history(WalletChain.ETHEREUM, replacement).isEmpty())
                assertEquals("legacy-hash", store.history(WalletChain.ETHEREUM, address).single().hash)
            }
        } finally {
            prefs.edit().clear().commit()
            context.deleteDatabase(databaseName)
        }
    }

    private inline fun WatchStore.useStore(block: (WatchStore) -> Unit) {
        try { block(this) } finally { close() }
    }
}
