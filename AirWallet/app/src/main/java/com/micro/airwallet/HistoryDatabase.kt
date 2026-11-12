package com.micro.airwallet

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

internal class HistoryDatabase(context: Context, name: String = "watch_history.db") :
    SQLiteOpenHelper(context, name, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE transactions (
                chain TEXT NOT NULL,
                address TEXT NOT NULL,
                tx_hash TEXT NOT NULL,
                amount TEXT NOT NULL,
                direction TEXT NOT NULL,
                block_time INTEGER NOT NULL,
                PRIMARY KEY (chain, address, tx_hash)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX transactions_recent ON transactions(chain, address, block_time DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun history(chain: WalletChain, address: String): List<HistoryEntry> {
        if (address.isBlank()) return emptyList()
        return readableDatabase.query(
            "transactions", arrayOf("tx_hash", "amount", "direction", "block_time"),
            "chain = ? AND address = ?", arrayOf(chain.name, addressKey(chain, address)),
            null, null, "block_time DESC, rowid DESC", "200"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(HistoryEntry(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
                }
            }
        }
    }

    fun upsert(chain: WalletChain, address: String, entries: List<HistoryEntry>) {
        if (address.isBlank() || entries.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            entries.asReversed().forEach { entry ->
                require(entry.hash.isNotBlank()) { "交易哈希不能为空" }
                val values = ContentValues().apply {
                    put("chain", chain.name)
                    put("address", addressKey(chain, address))
                    put("tx_hash", entry.hash)
                    put("amount", entry.amount)
                    put("direction", entry.direction)
                    put("block_time", entry.time)
                }
                db.insertWithOnConflict("transactions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun addressKey(chain: WalletChain, address: String) =
        if (chain.isEvm) address.lowercase(Locale.ROOT) else address
}
