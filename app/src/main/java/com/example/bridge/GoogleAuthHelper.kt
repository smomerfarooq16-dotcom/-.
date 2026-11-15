package com.example.bridge

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GoogleAuthHelper(
  private val activity: Activity,
  private val authResolutionLauncher: ActivityResultLauncher<IntentSenderRequest>
) {
  private var clientId: String = ""
  private var scopes: List<String> = listOf(
    "profile",
    "email",
    "https://www.googleapis.com/auth/drive.appdata"
  )
  private var pendingResolutionContinuation: ((Result<String>) -> Unit)? = null

  fun defaultWebClientId(): String {
    return try {
      activity.getString(R.string.default_web_client_id)
    } catch (_: Exception) {
      ""
    }
  }

  fun configure(serverClientId: String, requestedScopes: List<String>) {
    val trimmed = serverClientId.trim()
    clientId = if (trimmed.isNotBlank() && !trimmed.startsWith("PASTE_")) {
      trimmed
    } else {
      defaultWebClientId()
    }
    if (requestedScopes.isNotEmpty()) {
      scopes = requestedScopes
    }
  }

  suspend fun signIn(): JSONObject {
    val resolvedClientId = if (clientId.isNotBlank() && !clientId.startsWith("PASTE_")) {
      clientId
    } else {
      defaultWebClientId()
    }
    if (resolvedClientId.isBlank()) {
      throw IllegalStateException("Google Web Client ID is not configured.")
    }

    val cm = CredentialManager.create(activity)
    val signInOption = GetSignInWithGoogleOption.Builder(resolvedClientId).build()
    val request = GetCredentialRequest.Builder()
      .addCredentialOption(signInOption)
      .build()

    val result = try {
      cm.getCredential(activity, request)
    } catch (e: GetCredentialCancellationException) {
      Log.w("Auth", "Google Sign-In cancelled or dismissed: ${e.message}", e)
      throw IllegalStateException("Sign-in cancelled", e)
    }

    val cred = result.credential
    if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
      throw IllegalStateException("Unexpected credential type")
    }

    val googleCred = GoogleIdTokenCredential.createFrom(cred.data)
    val accessToken = authorizeDriveScopes()

    return JSONObject().apply {
      put("email", googleCred.id)
      put("name", googleCred.displayName ?: googleCred.id)
      put("givenName", googleCred.givenName ?: googleCred.displayName ?: "")
      put("authentication", JSONObject().apply {
        put("accessToken", accessToken)
        put("idToken", googleCred.idToken)
      })
    }
  }

  suspend fun refresh(): JSONObject {
    val accessToken = authorizeDriveScopes()
    return JSONObject().apply {
      put("accessToken", accessToken)
    }
  }

  suspend fun signOut() {
    try {
      CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest())
    } catch (_: Exception) {
    }
  }

  fun onResolutionResult(resultCode: Int, data: Intent?) {
    val cont = pendingResolutionContinuation ?: return
    pendingResolutionContinuation = null
    if (resultCode != Activity.RESULT_OK || data == null) {
      cont(Result.failure(IllegalStateException("Authorization cancelled")))
      return
    }
    try {
      val authResult = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
      val token = authResult.accessToken
      if (!token.isNullOrBlank()) {
        cont(Result.success(token))
      } else {
        cont(Result.failure(IllegalStateException("No access token returned")))
      }
    } catch (e: Exception) {
      cont(Result.failure(e))
    }
  }

  private suspend fun authorizeDriveScopes(): String {
    val scopeObjects = scopes.map { Scope(it) }
    val authRequest = AuthorizationRequest.builder()
      .setRequestedScopes(scopeObjects)
      .build()

    val authResult = suspendCancellableCoroutine { cont ->
      Identity.getAuthorizationClient(activity)
        .authorize(authRequest)
        .addOnSuccessListener { res -> cont.resume(res) }
        .addOnFailureListener { err -> cont.resumeWithException(err) }
    }

    if (authResult.hasResolution()) {
      val pendingIntent = authResult.pendingIntent
        ?: throw IllegalStateException("Missing consent PendingIntent")
      return suspendCancellableCoroutine { cont ->
        pendingResolutionContinuation = { result ->
          result.fold(
            onSuccess = { cont.resume(it) },
            onFailure = { cont.resumeWithException(it) }
          )
        }
        authResolutionLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
      }
    }

    return authResult.accessToken ?: throw IllegalStateException("Access token unavailable")
  }
}
