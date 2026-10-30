package com.dcontrol.client.core.network.credentials

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val Context.credentialsDataStore by preferencesDataStore(name = "device_credentials")

/** Stores the credentials as one AES-GCM ciphertext; the key never leaves the Android Keystore. */
@Singleton
class DataStoreCredentialStore @Inject constructor(
    @ApplicationContext context: Context,
    private val cipher: SecretCipher,
    private val json: Json,
) : CredentialStore {

    private val dataStore = context.credentialsDataStore

    override val credentials: Flow<StoredCredentials?> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[KEY]?.let(::decode) }
        .distinctUntilChanged()

    override suspend fun save(credentials: StoredCredentials) {
        val plain = json.encodeToString(StoredCredentials.serializer(), credentials).encodeToByteArray()
        val blob = Base64.encodeToString(cipher.encrypt(plain), Base64.NO_WRAP)
        dataStore.edit { it[KEY] = blob }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    // A blob that can't be decrypted (e.g. restored from a backup onto another device) counts as unpaired.
    private fun decode(blob: String): StoredCredentials? = try {
        val plain = cipher.decrypt(Base64.decode(blob, Base64.NO_WRAP))
        json.decodeFromString(StoredCredentials.serializer(), plain.decodeToString())
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SerializationException) {
        null
    }

    private companion object {
        val KEY = stringPreferencesKey("credentials")
    }
}
