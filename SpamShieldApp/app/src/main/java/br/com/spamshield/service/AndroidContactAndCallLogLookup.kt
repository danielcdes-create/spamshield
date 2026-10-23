package br.com.spamshield.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import br.com.spamshield.domain.ContactAndCallLogLookup
import br.com.spamshield.domain.PhoneNormalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidContactAndCallLogLookup @Inject constructor(
    @ApplicationContext private val context: Context
) : ContactAndCallLogLookup {

    override suspend fun isNumberInContacts(rawOrNormalizedNumber: String): Boolean =
        withContext(Dispatchers.IO) {
            if (rawOrNormalizedNumber.isBlank()) return@withContext false
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) return@withContext false

            try {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(rawOrNormalizedNumber)
                )
                context.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.PhoneLookup._ID),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    return@withContext cursor.moveToFirst()
                } ?: false
            } catch (_: Exception) {
                false
            }
        }

    override suspend fun hasRecentOutgoingCall(
        normalizedNumber: String,
        prefix6: String,
        sinceTimestampMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return@withContext false

        try {
            val selection = "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?"
            val selectionArgs = arrayOf(
                CallLog.Calls.OUTGOING_TYPE.toString(),
                sinceTimestampMs.toString()
            )
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                selection,
                selectionArgs,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                while (cursor.moveToNext()) {
                    val dialedRaw = if (numIdx >= 0) cursor.getString(numIdx) ?: "" else ""
                    if (dialedRaw.isNotBlank()) {
                        val normDialed = PhoneNormalizer.normalize(dialedRaw)
                        if (normDialed.normalizedNumber == normalizedNumber ||
                            (prefix6.isNotEmpty() && normDialed.prefix6 == prefix6)
                        ) {
                            return@withContext true
                        }
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }
}
