package ru.recipe.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiFailure(val status: Int, val code: String, override val message: String, val fields: Map<String,String> = emptyMap()) : Exception(message)

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("recipe_session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("recipe_session_v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("recipe_session_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun token(): String? = runCatching {
        val bytes = Base64.decode(prefs.getString("token", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
    }.getOrNull()
    fun save(token: String, user: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        prefs.edit().putString("token",Base64.encodeToString(cipher.iv+cipher.doFinal(token.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP))
            .putString("user",user.toString()).apply()
    }
    fun user(): JSONObject? = runCatching { JSONObject(prefs.getString("user",null) ?: return null) }.getOrNull()
    fun saveUser(user: JSONObject) { prefs.edit().putString("user",user.toString()).apply() }
    fun clear() { prefs.edit().remove("token").remove("user").apply() }
    var baseUrl: String
        get() = prefs.getString("base_url","http://10.0.2.2:8000")!!
        set(value) { prefs.edit().putString("base_url",value.trimEnd('/')).apply() }
}

class Api(private val store: SessionStore) {
    val client = OkHttpClient.Builder().callTimeout(15,TimeUnit.SECONDS).connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(15,TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .connectionPool(ConnectionPool(0,1,TimeUnit.SECONDS)).build()
    // Recover stale pooled connections for reads within the same 15-second call budget.
    // Mutations keep explicit retry so actions such as reaction toggles are never replayed.
    private val readClient = client.newBuilder().connectionPool(ConnectionPool()).retryOnConnectionFailure(true).build()
    private fun request(path: String, method: String, body: RequestBody? = null, key: String? = null): Request {
        val builder=Request.Builder().url(store.baseUrl+path).method(method,body)
        store.token()?.let { builder.header("Authorization","Bearer $it") }
        key?.let { builder.header("Idempotency-Key",it) }
        return builder.build()
    }
    private fun decode(response: Response): String {
        val text=response.body?.string().orEmpty()
        if(!response.isSuccessful) {
            val o=runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            val f=o.optJSONObject("fields")
            throw ApiFailure(response.code,o.optString("code","server_error"),
                o.optString("message","Не удалось выполнить запрос"), f?.keys()?.asSequence()?.associateWith { f.optString(it) } ?: emptyMap())
        }
        return text
    }
    suspend fun call(path: String, method: String="GET", data: JSONObject?=null, key: String?=null): String = withContext(Dispatchers.IO) {
        val body=if(method in listOf("GET","DELETE")) null else (data ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        try { (if(method=="GET") readClient else client).newCall(request(path,method,body,key)).execute().use(::decode) }
        catch(e: ApiFailure) { throw e }
        catch(e: IOException) {
            if(BuildConfig.DEBUG) android.util.Log.w("RecipeNetwork",e.javaClass.simpleName+": "+e.message)
            throw ApiFailure(0,"network","Нет соединения с сервером. Проверьте подключение и повторите.")
        }
    }
    suspend fun upload(bytes: ByteArray, mime: String, group: Long?): String = withContext(Dispatchers.IO) {
        val filename=if(mime=="image/png") "photo.png" else "photo.jpg"
        val body=MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file",filename,bytes.toRequestBody(mime.toMediaType())).build()
        try { client.newCall(request("/images"+(group?.let{"?group_id=$it"} ?: ""),"POST",body)).execute().use { JSONObject(decode(it)).getString("id") } }
        catch(e: ApiFailure) { throw e }
        catch(e: IOException) { throw ApiFailure(0,"network","Не удалось загрузить фото. Повторите.") }
    }
    suspend fun image(id: String): ByteArray = withContext(Dispatchers.IO) {
        readClient.newCall(request("/images/$id","GET")).execute().use {
            if(!it.isSuccessful) throw ApiFailure(it.code,"image_unavailable","Фото недоступно")
            it.body?.bytes() ?: byteArrayOf()
        }
    }
}
fun JSONObject.text(key: String, fallback: String=""): String = if(isNull(key)) fallback else optString(key,fallback)
fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.objects() ?: emptyList()
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
fun json(vararg pairs: Pair<String,Any?>): JSONObject = JSONObject().apply { pairs.forEach { (k,v)-> put(k,v ?: JSONObject.NULL) } }
