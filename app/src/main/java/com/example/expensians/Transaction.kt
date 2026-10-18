package com.example.expensians

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val senderName: String,            // other person's name, merchant, "Own account" or "Unknown"
    val amount: Long,                  // cents are dropped
    val type: String,                  // "DEBIT", "CREDIT" or "UNKNOWN"
    val reason: String,                // category reason ("Uncategorized", "Top-up", ...)
    val date: Long,                    // time the SMS was received
    val isParsed: Boolean,             // false = needs a look by the user
    val yourAccount: String = "",      // last 4 digits of the user's account
    val otherAccount: String? = null,  // last 4 digits of the other account (if any)
    val balance: Long? = null,         // balance after the transaction
    val isTopUp: Boolean = false,      // true only after the user confirmed a top-up
    val topUpState: String = "NONE",   // NONE, POSSIBLE, PROBABLE, CONFIRMED, REJECTED
    val autoLinked: Boolean = false,   // name was linked to an account by first name
    val nameConflict: Boolean = false, // same last-4 arrived with a different name
    val ownTransfer: Boolean = false   // money moved between the user's own accounts
)

// Last-4 digit ID -> name. Rows with isOwn = true are the user's own accounts.
@Entity(tableName = "known_accounts")
data class KnownAccount(
    @PrimaryKey val id: String,
    val name: String? = null,
    val isOwn: Boolean = false,
    val userSaved: Boolean = false,    // name typed by the user in settings (never overwritten)
    val lastSeen: Long = 0
)

// Debit amounts (100 or less) the user treats as possible top-ups.
@Entity(tableName = "topup_amounts")
data class TopUpAmount(@PrimaryKey val amount: Long)