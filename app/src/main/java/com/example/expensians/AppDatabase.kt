package com.example.expensians

import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Entity(tableName = "rules")
data class CategoryRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val minAmount: Double,
    val maxAmount: Double,
    val reason: String
)

@Dao
interface FinanceDao {
    @Query("SELECT * FROM transactions ORDER BY date DESC")
    fun getAllTransactions(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE isParsed = 0")
    fun getUnparsedTransactions(): Flow<List<Transaction>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: Transaction)

    @Update
    suspend fun updateTransaction(transaction: Transaction)

    @Delete
    suspend fun deleteTransaction(transaction: Transaction)

    @Query("SELECT * FROM rules")
    fun getAllRules(): Flow<List<CategoryRule>>

    @Query("SELECT * FROM rules")
    suspend fun getRulesOnce(): List<CategoryRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: CategoryRule)

    @Delete
    suspend fun deleteRule(rule: CategoryRule)

    // ---- account IDs and names ----
    @Query("SELECT * FROM known_accounts ORDER BY isOwn DESC, name")
    fun observeAccounts(): Flow<List<KnownAccount>>

    @Query("SELECT * FROM known_accounts")
    suspend fun getAccountsOnce(): List<KnownAccount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: KnownAccount)

    @Delete
    suspend fun deleteAccount(account: KnownAccount)

    // ---- top-up amounts ----
    @Query("SELECT * FROM topup_amounts ORDER BY amount")
    fun observeTopUps(): Flow<List<TopUpAmount>>

    @Query("SELECT amount FROM topup_amounts")
    suspend fun getTopUpAmountsOnce(): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTopUp(topUp: TopUpAmount)

    @Delete
    suspend fun deleteTopUp(topUp: TopUpAmount)

    // ---- goals ----
    @Query("SELECT * FROM goals ORDER BY id DESC")
    fun observeGoals(): Flow<List<Goal>>

    @Query("SELECT * FROM goals")
    suspend fun getGoalsOnce(): List<Goal>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGoal(goal: Goal)

    @Delete
    suspend fun deleteGoal(goal: Goal)

    // total of parsed, non-own-transfer messages of one type inside a time range
    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM transactions " +
                "WHERE isParsed = 1 AND ownTransfer = 0 AND type = :type " +
                "AND date >= :fromMs AND date < :toMs"
    )
    suspend fun sumBetween(type: String, fromMs: Long, toMs: Long): Long
}

@Database(
    entities = [
        Transaction::class,
        CategoryRule::class,
        KnownAccount::class,
        TopUpAmount::class,
        Goal::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun financeDao(): FinanceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Version 2 -> 3: adds the goals table and keeps every saved transaction.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `goals` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`kind` TEXT NOT NULL, " +
                            "`amount` INTEGER NOT NULL, " +
                            "`period` TEXT NOT NULL, " +
                            "`length` INTEGER NOT NULL, " +
                            "`unit` TEXT NOT NULL, " +
                            "`startDate` INTEGER NOT NULL, " +
                            "`notifiedWindow` INTEGER NOT NULL, " +
                            "`notifiedLevel` INTEGER NOT NULL)"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "expensians_db"
                )
                    .addMigrations(MIGRATION_2_3)
                    // Development only: any OTHER old version is erased.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                seedTopUps(context.applicationContext, instance)
                instance
            }
        }

        // Starter top-up list (5, 10, 15, 50, 100), added once.
        private fun seedTopUps(context: Context, db: AppDatabase) {
            val prefs = context.getSharedPreferences("expensians_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("topups_seeded_v2", false)) return
            prefs.edit().putBoolean("topups_seeded_v2", true).apply()
            CoroutineScope(Dispatchers.IO).launch {
                listOf(5L, 10L, 15L, 50L, 100L).forEach { db.financeDao().insertTopUp(TopUpAmount(it)) }
            }
        }
    }
}