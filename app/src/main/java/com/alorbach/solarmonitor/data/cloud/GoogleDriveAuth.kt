package com.alorbach.solarmonitor.data.cloud

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.alorbach.solarmonitor.BuildConfig
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.importing.SharedHttpClients
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.Request

class GoogleDriveNeedsUserException(message: String) : Exception(message)

sealed class GoogleDriveSignInStart {
    data class Completed(val email: String) : GoogleDriveSignInStart()
    data class NeedsUi(val intentSender: IntentSender) : GoogleDriveSignInStart()
}

class GoogleDriveAuth(
    context: Context,
    private val webClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID,
) {
    private val appContext = context.applicationContext

    fun isClientConfigured(): Boolean = webClientId.isNotBlank()

    fun isPlayServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) ==
            ConnectionResult.SUCCESS

    suspend fun beginSignIn(accountEmail: String? = null): GoogleDriveSignInStart {
        requireClientReady()
        val result = try {
            Identity.getAuthorizationClient(appContext)
                .authorize(authorizationRequest(accountEmail))
                .await()
        } catch (error: ApiException) {
            throw GoogleDriveNeedsUserException(userMessage(error))
        }
        if (result.hasResolution()) {
            val sender = result.pendingIntent?.intentSender
                ?: error(appContext.getString(R.string.backup_sign_in_failed))
            return GoogleDriveSignInStart.NeedsUi(sender)
        }
        val token = result.accessToken
            ?: throw GoogleDriveNeedsUserException(appContext.getString(R.string.backup_needs_reauth))
        val email = result.toGoogleSignInAccount()?.email ?: emailForToken(token)
        return GoogleDriveSignInStart.Completed(email)
    }

    suspend fun completeSignIn(data: Intent?): String {
        requireClientReady()
        val result = try {
            Identity.getAuthorizationClient(appContext).getAuthorizationResultFromIntent(data)
        } catch (error: ApiException) {
            throw GoogleDriveNeedsUserException(userMessage(error))
        }
        val token = result.accessToken
            ?: throw GoogleDriveNeedsUserException(appContext.getString(R.string.backup_needs_reauth))
        return result.toGoogleSignInAccount()?.email ?: emailForToken(token)
    }

    suspend fun accessToken(accountEmail: String? = null): String {
        requireClientReady()
        val result = try {
            Identity.getAuthorizationClient(appContext)
                .authorize(authorizationRequest(accountEmail))
                .await()
        } catch (error: ApiException) {
            throw GoogleDriveNeedsUserException(userMessage(error))
        }
        if (result.hasResolution()) {
            throw GoogleDriveNeedsUserException(appContext.getString(R.string.backup_needs_reauth))
        }
        return result.accessToken
            ?: throw GoogleDriveNeedsUserException(appContext.getString(R.string.backup_needs_reauth))
    }

    @Suppress("DEPRECATION")
    suspend fun signOut() {
        runCatching { Identity.getSignInClient(appContext).signOut().await() }
    }

    private fun authorizationRequest(accountEmail: String?): AuthorizationRequest {
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .requestOfflineAccess(webClientId)
        val email = accountEmail?.trim().orEmpty()
        if (email.isNotBlank()) {
            builder.setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
        }
        return builder.build()
    }

    private fun requireClientReady() {
        check(isClientConfigured()) { appContext.getString(R.string.backup_sign_in_not_built) }
        check(isPlayServicesAvailable()) {
            appContext.getString(R.string.backup_play_services_missing)
        }
    }

    private suspend fun emailForToken(accessToken: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(DRIVE_ABOUT_URL)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        SharedHttpClients.okHttp.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Drive request failed: ${response.code}" }
            val json = response.body?.string().orEmpty()
            DriveJson.stringField(json, "emailAddress")
                ?: error(appContext.getString(R.string.backup_sign_in_failed))
        }
    }

    private fun userMessage(error: ApiException): String {
        val detail = error.message
        return if (detail.isNullOrBlank()) {
            appContext.getString(R.string.backup_sign_in_failed)
        } else {
            appContext.getString(R.string.backup_sign_in_failed_detail, detail)
        }
    }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val DRIVE_ABOUT_URL =
            "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)"
    }
}
