package com.micro.airvault

import android.content.Context
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Transaction
import androidx.room3.migration.Migration
import com.micro.airprotocol.BitcoinAddressType
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver

internal data class StoredWallet(
    val id: String,
    val name: String,
    @ColumnInfo(name = "bitcoin_type") val bitcoinAddressType: BitcoinAddressType = BitcoinAddressType.SEGWIT
)

@Entity(tableName = "wallets")
internal data class WalletEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mnemonic: ByteArray,
    val iv: ByteArray,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "bitcoin_type", defaultValue = "'SEGWIT'")
    val bitcoinAddressType: BitcoinAddressType = BitcoinAddressType.SEGWIT
)

@Entity(
    tableName = "vault_state",
    foreignKeys = [ForeignKey(
        entity = WalletEntity::class,
        parentColumns = ["id"], childColumns = ["wallet_id"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("wallet_id")]
)
internal data class VaultStateEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "wallet_id") val walletId: String? = null
)

@Dao
internal abstract class WalletDao {
    @Query("SELECT * FROM custom_chains ORDER BY createdAt, rowid")
    abstract suspend fun customChains(): List<CustomChain>

    @Insert
    protected abstract suspend fun insertCustomChain(chain: CustomChain)

    @Transaction
    open suspend fun addCustomChain(chain: CustomChain) {
        require(customChains().none { it.name.equals(chain.name, ignoreCase = true) }) { "链名称与已有链重复" }
        insertCustomChain(chain)
    }

    @Query("SELECT id, name, bitcoin_type FROM wallets ORDER BY created_at, rowid")
    abstract suspend fun wallets(): List<StoredWallet>

    @Query("SELECT id, name, bitcoin_type FROM wallets WHERE id = (SELECT wallet_id FROM vault_state WHERE id = 1)")
    abstract suspend fun currentWallet(): StoredWallet?

    @Query("SELECT * FROM wallets WHERE id = :id")
    abstract suspend fun wallet(id: String): WalletEntity?

    @Insert
    abstract suspend fun insert(wallet: WalletEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertLegacy(wallet: WalletEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun ensureState(state: VaultStateEntity)

    @Query("UPDATE vault_state SET wallet_id = :id WHERE id = 1")
    abstract suspend fun setCurrent(id: String)

    @Query("DELETE FROM wallets WHERE id = :id")
    abstract suspend fun deleteById(id: String): Int

    @Query("UPDATE wallets SET bitcoin_type = :type WHERE id = :id")
    abstract suspend fun setBitcoinAddressType(id: String, type: BitcoinAddressType): Int

    @Query("UPDATE wallets SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: String, name: String): Int

    @Transaction
    open suspend fun save(wallet: WalletEntity) {
        insert(wallet)
        ensureState(VaultStateEntity())
        setCurrent(wallet.id)
    }

    @Transaction
    open suspend fun migrateLegacy(wallet: WalletEntity) {
        insertLegacy(wallet)
        ensureState(VaultStateEntity())
        if (currentWallet() == null) setCurrent(wallet.id)
    }

    @Transaction
    open suspend fun select(id: String) {
        check(wallets().any { it.id == id }) { "钱包不存在" }
        setCurrent(id)
    }

    @Transaction
    open suspend fun delete(id: String) {
        check(deleteById(id) == 1) { "钱包不存在" }
        if (currentWallet() == null) wallets().firstOrNull()?.let { setCurrent(it.id) }
    }
}

@Database(entities = [WalletEntity::class, VaultStateEntity::class, CustomChain::class], version = 4, exportSchema = true)
internal abstract class VaultDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE wallets ADD COLUMN bitcoin_type TEXT NOT NULL DEFAULT 'SEGWIT'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("CREATE TABLE IF NOT EXISTS custom_chains (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, derivationPath TEXT NOT NULL, addressFormat TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_custom_chains_name ON custom_chains(name)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE custom_chains ADD COLUMN chain_id INTEGER DEFAULT NULL")
            }
        }

        fun open(context: Context, name: String, derivePassword: () -> ByteArray): VaultDatabase =
            Room.databaseBuilder(context, VaultDatabase::class.java, name)
                .setDriver(VaultSqlCipherDriver(derivePassword))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .setJournalMode(JournalMode.TRUNCATE)
                .setQueryCoroutineContext(Dispatchers.IO)
                .addCallback(object : Callback() {
                    override suspend fun onOpen(connection: SQLiteConnection) {
                        connection.execSQL("PRAGMA secure_delete = ON")
                    }
                })
                .build()
    }
}

/** SQLCipher retains its password for its internal connection pool; wipe it when that pool closes. */
internal class VaultSqlCipherDriver(private val derivePassword: () -> ByteArray) : SQLiteDriver {
    init {
        System.loadLibrary("sqlcipher")
    }

    override val hasConnectionPool: Boolean = true

    override fun open(fileName: String): SQLiteConnection {
        val password = derivePassword()
        try {
            val handler = DatabaseErrorHandler { _, cause ->
                throw IllegalStateException("无法解密钱包数据库，原始数据已保留", cause)
            }
            val connection = SQLCipherDriver(password, null, handler).open(fileName)
            return object : SQLiteConnection by connection {
                override fun close() {
                    try {
                        connection.close()
                    } finally {
                        SodiumMemory.wipe(password)
                    }
                }
            }
        } catch (exception: Throwable) {
            SodiumMemory.wipe(password)
            throw exception
        }
    }
}
