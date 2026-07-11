package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.CallRecordDao
import com.example.data.dao.ContactDao
import com.example.data.dao.IceServerDao
import com.example.data.entity.CallRecord
import com.example.data.entity.Contact
import com.example.data.entity.IceServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [Contact::class, CallRecord::class, IceServer::class],
    version = 1,
    exportSchema = false
)
abstract class CallLinkDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao
    abstract fun callRecordDao(): CallRecordDao
    abstract fun iceServerDao(): IceServerDao

    companion object {
        @Volatile
        private var INSTANCE: CallLinkDatabase? = null

        fun getDatabase(context: Context): CallLinkDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CallLinkDatabase::class.java,
                    "calllink_database"
                )
                .fallbackToDestructiveMigration()
                .addCallback(DatabaseCallback())
                .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        populateDb(database)
                    }
                }
            }

            suspend fun populateDb(db: CallLinkDatabase) {
                val contactDao = db.contactDao()
                val iceDao = db.iceServerDao()
                val callDao = db.callRecordDao()

                // Default Contacts
                contactDao.insertContact(Contact("+1555019283", "Alice Rogers", "", isFavorite = true, status = "Online"))
                contactDao.insertContact(Contact("+1555014852", "Bob Miller", "", isFavorite = false, status = "Offline"))

                // Default ICE Servers
                iceDao.insertIceServer(IceServer(label = "Google STUN 1", url = "stun:stun.l.google.com:19302", isTurn = false))
                iceDao.insertIceServer(IceServer(label = "Google STUN 2", url = "stun:stun1.l.google.com:19302", isTurn = false))
                iceDao.insertIceServer(IceServer(label = "Default TURN Relay", url = "turn:turn.calllink.io:3478", username = "demo_user", credential = "demo_password", isTurn = true))

                // Default Call History to look rich and realistic out of the box
                val now = System.currentTimeMillis()
                callDao.insertCallRecord(CallRecord(contactName = "Alice Rogers", contactPhone = "+1555019283", isVoice = false, callType = "Incoming", timestamp = now - 3600000 * 2, durationSeconds = 145))
                callDao.insertCallRecord(CallRecord(contactName = "Bob Miller", contactPhone = "+1555014852", isVoice = true, callType = "Missed", timestamp = now - 3600000 * 5, durationSeconds = 0))
            }
        }
    }
}
