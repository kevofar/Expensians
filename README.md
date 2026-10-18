# Expensians

**An offline Android app that turns your bank SMS into a private expense tracker.**

Expensians reads the transaction messages that **CBE (Commercial Bank of Ethiopia)** sends to your phone, understands them, and keeps a clean history of what you spent and what you earned. On top of that you can set spending limits and earning goals and get notified when you hit them (with crazy sounds). Everything stays on your phone: no account, no server, no sharing data or anything.

## App Screenshots

<p align="center">
  <img width="180" alt="Home Screen" src="https://github.com/user-attachments/assets/61098ef3-e407-4081-aa9d-c4c67189d256" />
  <img width="180" alt="Transactions List" src="https://github.com/user-attachments/assets/ed410363-8a86-4b34-a721-db124a0ff819" />
  <img width="180" alt="Goals & Spending Limits" src="https://github.com/user-attachments/assets/b18673f3-fb95-4fff-82a7-16fc81a873e0" />
  <img width="180" alt="Database Management" src="https://github.com/user-attachments/assets/92247d3d-9e79-4f3c-8ec2-9c0ec6ef3662" />
  <img width="180" alt="Settings & Customization" src="https://github.com/user-attachments/assets/5bc80631-ec80-43d9-b0a3-e5ac2d6a9a8c" />
</p>

## Features

**Automatic tracking**
- Catches incoming CBE messages the moment they arrive and saves them as transactions.
- Long messages that arrive in several parts are joined back together.
- Messages that are not transactions (OTP codes, promotions, notices and warnings) are ignored.
- Anything the app cannot read with confidence is saved as **Needs review** instead of being guessed.

**Four tabs**
- **Home**: today's spending, how many messages are saved, and recent transactions.
- **Database**: the full history. Press and hold a message to select it. Then use the buttons at the top right:
  - **Set reason** for one or many messages (shown as "Reason: Unknown" when empty)
  - **Edit** one message (amount, direction, name, accounts, balance, date, reason)
  - **Delete** one or many, with an "are you sure" step
- **Goal**: spending limits and earning goals (see below).
- **Settings**: dark mode, your own accounts, top-up amounts, and names for account IDs.

**Smart handling of people and accounts**
- Learns which name belongs to which 4-digit account ID and fills it in next time.
- A name you type yourself is never overwritten by a name from a message.
- Transfers between your own accounts are labelled "Own account" and are not counted as spending.
- Messages that only contain a name are linked to a known account by first name.
- Small unnamed debits (100 ETB or less) are flagged as possible mobile top-ups. You confirm with Yes or No, and your confirmed amounts are remembered.

**Goals and notifications**
- **Spending limit**: your spending in the period should stay under an amount.
- **Earning goal**: your income in the period should reach an amount.
  *(You are notified with a message when you reach 80% of your plan, and notified with a sound when you reach 100% of it)*
- Periods: daily, weekly (Monday to Sunday), monthly, 6 months, 1 year, or custom (days, weeks, months or years).
- Progress bars with days left and a suggested daily amount.
- A normal notification at 80% of a limit, and your own short sounds when a limit is reached (`why.mp3`) or an earning goal is reached (`how.mp3`). Each is sent once per period.
- Optional volume boost to 50% right before the sound plays.

**Your data stays yours**
- Export everything as **JSON** (full backup) or transactions as **CSV** (opens in Excel or Google Sheets).
- Import a JSON or CSV file made by the app. Importing only adds data, skips what is already there, and is safe to run twice.
- Editing, deleting, exporting and importing ask for the last 4 digits of one of your own accounts (also known as Account ID). This is a simple confirmation, **not strong security**.

## How the message reader works

The reader is plain logic, kept separate from the Android code, and was designed from a study of about 600 real CBE messages.

1. A message is a transaction only if it contains an account representation (a `1`, some stars, then 4 digits) **and** an `ETB` amount.
2. It is a **debit** if it contains "debit" or "transfer".
3. Otherwise it is a **credit** if it contains "credit" or "receive".
4. The amount is the first number after the first `ETB`. Cents are dropped.
5. The balance is the number after the word "current" or "balance".
6. An account ID and a name seen together are saved as a pair. If the same ID later arrives with a different name, the saved name is kept and the conflict is flagged.
7. Names saved by the user always win.
8. Your own accounts are recognised and labelled "Own account".
9. Name-only messages are linked by first name to the most recently seen matching account.
10. A debit with no name, no other account and an amount of 100 or less is a **possible top-up**.
11. If that amount is on your top-up list it is a **probable top-up**.
12. When you confirm a top-up, its amount is added to the list.

If the direction or the balance cannot be found, the message is saved as **Needs review**.

## Tech stack

- **Kotlin**
- **Jetpack Compose** with Material 3
- **Room** database (with KSP), including a migration so updates keep your data
- **Coroutines and Flow**
- Notification channels with custom sounds
- Storage Access Framework for export and import (no storage permission needed)
- Min SDK 24 (Android 7.0)

## Project structure

| File | What it does |
|---|---|
| `Transaction.kt` | Data models: transactions, known accounts, top-up amounts |
| `AppDatabase.kt` | Room database, queries, and the version 2 to 3 migration |
| `SmsReceiver.kt` | Receives SMS, saves them, and holds the message-reading logic |
| `Goal.kt` | Goal model and the logic for periods and progress |
| `GoalNotifier.kt` | Notifications, sounds and the volume boost |
| `GoalScreen.kt` | The Goal tab |
| `DatabaseTools.kt` | App shell, Database tab, selection actions, edit, export |
| `DataImport.kt`, `ImportAll.kt` | Import of JSON and CSV files |
| `MainActivity.kt` | Activity, theme, Home and Settings |

## How it was built

1. **Study the data first.** About 600 real messages were analysed to find the message templates and write down the rules above before any app code existed.
2. **Parser as pure logic.** The reader has no Android code, so it is easy to test and change.
3. **Database and receiver.** Room stores transactions, account names, top-up amounts and goals. A broadcast receiver filters messages by sender and saves them in the background.
4. **Screens in Compose.** Home, Database, Goal and Settings, with a warm light and dark theme.
5. **Features added step by step.** Review and edit, multi-select actions, goals with notifications, then export and import.
6. **Built with AI assistance.** The app was developed step by step in conversation with an AI assistant (Claude by Anthropic), with all testing done on a real phone.

> **Note for anyone building something similar:** Room's code generator needs the **KSP** plugin for Kotlin code. Using `annotationProcessor` instead makes the app crash at launch with `AppDatabase_Impl does not exist`.

## Getting started

**Requirements:** A recent Android Studio, and a phone or emulator running Android 7.0 or newer.

1. Clone the repository and open it in Android Studio.
2. Add two short sound files named `why.mp3` and `how.mp3` to `app/src/main/res/raw/`. The goal notifications expect them, and the project will not build without them.
3. Click **Sync Now**, then **Run**.
4. On first launch, enter the last 4 digits of your own CBE account and allow the SMS and notification permissions.

**Testing without a bank:** In the emulator, open Extended controls, then Phone, then SMS, and send a message with the sender set to `CBE`, for example:

```text
Dear Customer, You have successfully transferred ETB 500.00 from account 1*********1234 to account 1*********5678 (Sample Name) on 10/18/2026 at 10:15:00. Your current balance is ETB 9,500.00.
