package com.example.data.repository

import com.example.data.dao.CallRecordDao
import com.example.data.dao.ContactDao
import com.example.data.dao.IceServerDao
import com.example.data.entity.CallRecord
import com.example.data.entity.Contact
import com.example.data.entity.IceServer
import kotlinx.coroutines.flow.Flow

class CallLinkRepository(
    private val contactDao: ContactDao,
    private val callRecordDao: CallRecordDao,
    private val iceServerDao: IceServerDao
) {
    // Contacts Flow
    val allContacts: Flow<List<Contact>> = contactDao.getAllContacts()
    val favoriteContacts: Flow<List<Contact>> = contactDao.getFavoriteContacts()

    suspend fun getContactByPhone(phone: String): Contact? {
        return contactDao.getContactByPhone(phone)
    }

    suspend fun insertContact(contact: Contact) {
        contactDao.insertContact(contact)
    }

    suspend fun deleteContact(contact: Contact) {
        contactDao.deleteContact(contact)
    }

    suspend fun updateContactStatus(phone: String, status: String) {
        contactDao.updateStatus(phone, status, System.currentTimeMillis())
    }

    suspend fun setContactBlocked(phone: String, isBlocked: Boolean) {
        contactDao.setBlocked(phone, isBlocked)
    }

    suspend fun setContactFavorite(phone: String, isFavorite: Boolean) {
        contactDao.setFavorite(phone, isFavorite)
    }

    // Call Records Flow
    val allCallRecords: Flow<List<CallRecord>> = callRecordDao.getAllCallRecords()

    suspend fun insertCallRecord(record: CallRecord) {
        callRecordDao.insertCallRecord(record)
    }

    suspend fun deleteCallRecordById(id: Long) {
        callRecordDao.deleteCallRecordById(id)
    }

    suspend fun hasCallRecordWithTimestamp(timestamp: Long): Boolean {
        return callRecordDao.getCountByTimestamp(timestamp) > 0
    }

    suspend fun clearCallHistory() {
        callRecordDao.deleteAllCallRecords()
    }

    // ICE Servers Flow
    val allIceServers: Flow<List<IceServer>> = iceServerDao.getAllIceServers()

    suspend fun insertIceServer(server: IceServer) {
        iceServerDao.insertIceServer(server)
    }

    suspend fun deleteIceServer(server: IceServer) {
        iceServerDao.deleteIceServer(server)
    }

    suspend fun clearIceServers() {
        iceServerDao.clearAllIceServers()
    }
}
