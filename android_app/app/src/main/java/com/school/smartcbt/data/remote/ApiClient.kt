package com.school.smartcbt.data.remote

import android.content.Context
import android.content.Intent
import com.school.smartcbt.ui.LoginActivity
import com.school.smartcbt.utils.SessionManager
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.Route
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    const val BASE_URL: String = "https://cbt.smpn1boyolangu.my.id"

    @Volatile
    private var apiServiceInstance: ApiService? = null

    @Volatile
    private var okHttpClientInstance: OkHttpClient? = null

    @Volatile
    private var lastCalculatedBaseUrl: String? = null

    private val lock = Any()

    fun getBaseServerUrl(context: Context): String {
        val sessionManager = SessionManager(context)
        var serverIp = sessionManager.getServerIp().trim()
        if (serverIp.contains("localhost") || serverIp.contains("127.0.0.1")) {
            val hostReplacement = if (sessionManager.isOutsideSchoolOrCellular()) "cbt.smpn1boyolangu.my.id" else "192.168.101.46"
            serverIp = serverIp.replace("localhost", hostReplacement).replace("127.0.0.1", hostReplacement)
        }
        val baseUrl = if (serverIp.startsWith("http://") || serverIp.startsWith("https://")) {
            serverIp
        } else {
            "http://$serverIp"
        }
        return baseUrl.trimEnd('/')
    }

    fun getFullApiBaseUrl(context: Context): String {
        var baseUrl = getBaseServerUrl(context)
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/"
        }
        if (!baseUrl.endsWith("api/")) {
            baseUrl += "api/"
        }
        return baseUrl
    }

    fun getClient(context: Context): ApiService {
        val fullBaseUrl = getFullApiBaseUrl(context)
        val sessionManager = SessionManager(context.applicationContext)

        return apiServiceInstance?.takeIf { lastCalculatedBaseUrl == fullBaseUrl }
            ?: synchronized(lock) {
                apiServiceInstance?.takeIf { lastCalculatedBaseUrl == fullBaseUrl } ?: run {
                    val authInterceptor = Interceptor { chain ->
                        val original = chain.request()
                        val token = sessionManager.getToken()
                        val requestBuilder = original.newBuilder()
                        if (!token.isNullOrEmpty()) {
                            requestBuilder.header("Authorization", "Bearer $token")
                        }
                        requestBuilder.header("Accept", "application/json")
                        requestBuilder.header("X-Client-Type", "SmartSchool-ExamBrowser")
                        requestBuilder.header("User-Agent", "SmartSchool-ExamBrowser/2.8.58 (Android; Linux)")
                        chain.proceed(requestBuilder.build())
                    }

                    val tokenAuthenticator = Authenticator { _: Route?, response: Response ->
                        // Prevent infinite loop if refresh token is rejected or repeated failure
                        if (responseCount(response) >= 2) {
                            return@Authenticator null
                        }

                        val refreshToken = sessionManager.getRefreshToken()
                        if (refreshToken.isNullOrEmpty()) {
                            return@Authenticator null
                        }

                        synchronized(lock) {
                            val currentToken = sessionManager.getToken()
                            val originalHeaderToken = response.request().header("Authorization")
                                ?.removePrefix("Bearer ")
                                ?.trim()

                            // If another thread already refreshed the token, retry with currentToken immediately
                            if (!currentToken.isNullOrEmpty() && currentToken != originalHeaderToken) {
                                return@Authenticator response.request().newBuilder()
                                    .header("Authorization", "Bearer $currentToken")
                                    .build()
                            }

                            // Perform synchronous refresh call using a raw OkHttpClient
                            try {
                                val refreshBodyJson = "{\"refreshToken\":\"$refreshToken\"}"
                                val mediaType = MediaType.parse("application/json; charset=utf-8")
                                val requestBody = RequestBody.create(mediaType, refreshBodyJson)
                                val refreshRequest = Request.Builder()
                                    .url(fullBaseUrl + "auth/refresh")
                                    .post(requestBody)
                                    .header("Accept", "application/json")
                                    .header("X-Client-Type", "SmartSchool-ExamBrowser")
                                    .header("User-Agent", "SmartSchool-ExamBrowser/2.8.58 (Android; Linux)")
                                    .build()

                                val rawClient = OkHttpClient.Builder()
                                    .connectTimeout(30, TimeUnit.SECONDS)
                                    .readTimeout(60, TimeUnit.SECONDS)
                                    .writeTimeout(60, TimeUnit.SECONDS)
                                    .build()

                                val refreshResponse = rawClient.newCall(refreshRequest).execute()
                                if (refreshResponse.isSuccessful) {
                                    val responseString = refreshResponse.body()?.string() ?: ""
                                    val jsonObject = JSONObject(responseString)
                                    val newToken = jsonObject.optString("token")
                                    if (newToken.isNotEmpty()) {
                                        sessionManager.saveToken(newToken)
                                        return@Authenticator response.request().newBuilder()
                                            .header("Authorization", "Bearer $newToken")
                                            .build()
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            return@Authenticator null
                        }
                    }

                    val failoverInterceptor = Interceptor { chain ->
                        val request = chain.request()
                        try {
                            val response = chain.proceed(request)
                            // Jika response server gateway error 502/503/504
                            if (response.code() in listOf(502, 503, 504)) {
                                val isOutside = sessionManager.isOutsideSchoolOrCellular()
                                if (!isOutside) {
                                    val nextCandidate = sessionManager.rotateToNextServer()
                                    // JANGAN rotasi ke 192.168.101.46 jika di luar sekolah
                                    if (!nextCandidate.contains("192.168.101.46")) {
                                        resetClient()
                                        val nextHttpUrl = okhttp3.HttpUrl.parse(nextCandidate)
                                        if (nextHttpUrl != null) {
                                            val newUrl = request.url().newBuilder()
                                                .scheme(nextHttpUrl.scheme())
                                                .host(nextHttpUrl.host())
                                                .port(nextHttpUrl.port())
                                                .build()
                                            response.close()
                                            return@Interceptor chain.proceed(request.newBuilder().url(newUrl).build())
                                        }
                                    }
                                }
                                // Pastikan fallback hanya ke domain resmi yang valid
                                sessionManager.setServerIp(SessionManager.ONLINE_SERVER_URL)
                            }
                            response
                        } catch (e: java.io.IOException) {
                            android.util.Log.w("ApiClient", "Network error on ${request.url()}: ${e.message}")
                            val isOutside = sessionManager.isOutsideSchoolOrCellular()
                            // JANGAN rotasi ke 192.168.101.46 jika user sedang berada di jaringan internet publik/seluler atau host tidak dapat dihubungi
                            if (!isOutside) {
                                val nextCandidate = sessionManager.rotateToNextServer()
                                if (!nextCandidate.contains("192.168.101.46")) {
                                    resetClient()
                                    val nextHttpUrl = okhttp3.HttpUrl.parse(nextCandidate)
                                    if (nextHttpUrl != null) {
                                        val newUrl = request.url().newBuilder()
                                            .scheme(nextHttpUrl.scheme())
                                            .host(nextHttpUrl.host())
                                            .port(nextHttpUrl.port())
                                            .build()
                                        return@Interceptor chain.proceed(request.newBuilder().url(newUrl).build())
                                    }
                                }
                            }
                            // Pastikan fallback hanya ke domain resmi yang valid
                            sessionManager.setServerIp(SessionManager.ONLINE_SERVER_URL)
                            throw e
                        }
                    }

                    val authErrorInterceptor = Interceptor { chain ->
                        val response = chain.proceed(chain.request())
                        if (response.code() == 401) {
                            try {
                                val peek = response.peekBody(2048).string()
                                if (peek.contains("FORCE_LOGOUT", ignoreCase = true) || peek.contains("\"forceLogout\":true") || peek.contains("\"forceLogout\": true")) {
                                    sessionManager.clearSession()
                                    val logoutIntent = Intent(context.applicationContext, LoginActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        putExtra("EXTRA_FORCE_LOGOUT", true)
                                    }
                                    context.applicationContext.startActivity(logoutIntent)
                                }
                            } catch (e: Exception) {
                                // Safe fallback
                            }
                        }
                        response
                    }

                    val okHttpClient = OkHttpClient.Builder()
                        .addInterceptor(failoverInterceptor)
                        .addInterceptor(authInterceptor)
                        .addInterceptor(authErrorInterceptor)
                        .authenticator(tokenAuthenticator)
                        .connectionPool(okhttp3.ConnectionPool(10, 2, TimeUnit.MINUTES))
                        .connectTimeout(45, TimeUnit.SECONDS)
                        .readTimeout(120, TimeUnit.SECONDS)
                        .writeTimeout(120, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(true)
                        .build()

                    val retrofit = Retrofit.Builder()
                        .baseUrl(fullBaseUrl)
                        .client(okHttpClient)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()

                    val service = retrofit.create(ApiService::class.java)
                    lastCalculatedBaseUrl = fullBaseUrl
                    okHttpClientInstance = okHttpClient
                    apiServiceInstance = service
                    service
                }
            }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse()
        while (prior != null) {
            count++
            prior = prior.priorResponse()
        }
        return count
    }

    fun resetClient() {
        synchronized(lock) {
            apiServiceInstance = null
            okHttpClientInstance = null
            lastCalculatedBaseUrl = null
        }
    }
}
