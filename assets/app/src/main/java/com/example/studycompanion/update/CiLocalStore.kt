package com.example.studycompanion.update

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable data class CiPending(
    val repository: String, val branch: String, val target: String, val requestId: String,
    val createdAt: Long, val runId: Long = 0, val autoApply: Boolean = true
)
@Serializable data class CiSavedState(val pending: CiPending? = null, val importedRun: Long = 0)
/** Both files are excluded from backup. The token is additionally encrypted by Android Keystore. */
class CiLocalStore(context: Context) {
    private val secret=AtomicFile(File(context.noBackupFilesDir,"github-ci-token.enc"))
    private val state=AtomicFile(File(context.noBackupFilesDir,"github-ci-state.json"))
    private val json=Json { ignoreUnknownKeys=true }
    private val alias="studygadget.github-ci.v1"
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun write(file: AtomicFile, data: ByteArray) {
        val stream=file.startWrite()
        try { stream.write(data);file.finishWrite(stream) } catch(e: Exception) { file.failWrite(stream);throw e }
    }
    fun saveConfig(config: CiConfig) = synchronized(lock) {
        val data=buildJsonObject { put("repository",config.repository);put("branch",config.branch);put("token",config.token) }.toString().toByteArray()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val encrypted=cipher.doFinal(data)
        write(secret,byteArrayOf(cipher.iv.size.toByte())+cipher.iv+encrypted)
    }
    fun config(): CiConfig? = synchronized(lock) {
        if(!secret.baseFile.exists())return@synchronized null
        val data=secret.readFully();require(data.size in 30..4096)
        val ivLength=data[0].toInt() and 255;require(ivLength==12 && data.size>1+ivLength+16)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,data.copyOfRange(1,1+ivLength)))
        }
        val o=json.parseToJsonElement(cipher.doFinal(data.copyOfRange(1+ivLength,data.size)).toString(Charsets.UTF_8)).jsonObject
        CiConfig(o.getValue("repository").jsonPrimitive.content,o.getValue("branch").jsonPrimitive.content,o.getValue("token").jsonPrimitive.content)
    }
    fun state(): CiSavedState = synchronized(lock) {
        if(!state.baseFile.exists())return@synchronized CiSavedState()
        val bytes=state.readFully();require(bytes.size<=8192)
        runCatching { json.decodeFromString<CiSavedState>(bytes.toString(Charsets.UTF_8)) }.getOrElse { state.delete();CiSavedState() }
    }
    fun pending(pending: CiPending?) = synchronized(lock) { write(state,json.encodeToString(state().copy(pending=pending)).toByteArray()) }
    fun imported(runId: Long) = synchronized(lock) { write(state,json.encodeToString(state().copy(importedRun=runId)).toByteArray()) }
    fun disconnect() = synchronized(lock) { secret.delete();state.delete();KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(alias) } }
    companion object { private val lock=Any() }
}
