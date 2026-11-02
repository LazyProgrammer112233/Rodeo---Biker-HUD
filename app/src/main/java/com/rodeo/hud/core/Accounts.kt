package com.rodeo.hud.core

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.rodeo.hud.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/** Who is signed in, as the screens need it. */
data class Account(
    val uid: String,
    val email: String?,
    val name: String?,
    /** Signed in with Google (email verified by Google). */
    val google: Boolean,
    val verified: Boolean,
) {
    /** Can use the app: Google, or an email account whose address has been confirmed. */
    val ready get() = google || verified
}

/**
 * Rider accounts with Firebase Authentication (free): Continue with Google, or email and password
 * with a verification link, a reset link for a forgotten password, sign-out and account deletion.
 * Before signing in the phone has an anonymous id (used by pack mode); creating an account keeps it.
 */
class Accounts(context: Context) {
    private val auth: FirebaseAuth? = runCatching {
        FirebaseApp.getApps(context).firstOrNull()?.let { FirebaseAuth.getInstance(it) }
    }.getOrNull()

    /** Accounts are set up in this build (Firebase keys present). Without them the app runs signed out. */
    val enabled: Boolean get() = auth != null

    /** Google sign-in needs the web client id that Firebase adds once the app's SHA-1 is registered. */
    val googleEnabled: Boolean get() = enabled && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    private val _account = MutableStateFlow(read(auth?.currentUser))
    val account: StateFlow<Account?> = _account.asStateFlow()

    init {
        auth?.addAuthStateListener { _account.value = read(it.currentUser) }
    }

    private fun read(u: FirebaseUser?): Account? {
        if (u == null || u.isAnonymous) return null
        val google = u.providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID }
        return Account(u.uid, u.email, u.displayName, google, u.isEmailVerified)
    }

    private fun refresh() { _account.value = read(auth?.currentUser) }

    sealed interface Result {
        data object Ok : Result
        /** Account made or signed in, but the email address still has to be confirmed. */
        data object NeedsVerification : Result
        data class Error(val message: String) : Result
    }

    suspend fun signIn(email: String, password: String): Result = guard {
        val a = auth!!
        a.signInWithEmailAndPassword(email.trim(), password).await()
        val u = a.currentUser
        if (u != null && !u.isEmailVerified) Result.NeedsVerification else Result.Ok
    }

    /** New account: keeps the phone's anonymous id when there is one, then sends the verification link. */
    suspend fun create(email: String, password: String, name: String): Result = guard {
        val a = auth!!
        val credential = EmailAuthProvider.getCredential(email.trim(), password)
        val current = a.currentUser
        val user = if (current != null && current.isAnonymous) current.linkWithCredential(credential).await().user
        else a.createUserWithEmailAndPassword(email.trim(), password).await().user
        if (name.isNotBlank()) {
            runCatching {
                user?.updateProfile(com.google.firebase.auth.UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build())?.await()
            }
        }
        user?.sendEmailVerification()?.await()
        refresh()
        Result.NeedsVerification
    }

    suspend fun resendVerification(): Result = guard {
        auth!!.currentUser?.sendEmailVerification()?.await()
        Result.NeedsVerification
    }

    /** Asks Firebase whether the link in the email has been clicked yet. */
    suspend fun checkVerified(): Result = guard {
        auth!!.currentUser?.reload()?.await()
        refresh()
        if (auth.currentUser?.isEmailVerified == true) Result.Ok else Result.Error("Not confirmed yet. Open the link in the email, then tap again.")
    }

    suspend fun sendReset(email: String): Result = guard {
        auth!!.sendPasswordResetEmail(email.trim()).await()
        Result.Ok
    }

    /** Continue with Google, through Android's account picker (Credential Manager). */
    suspend fun google(activity: Activity): Result = guard {
        if (!googleEnabled) return@guard Result.Error("Google sign-in isn't set up in this build yet. Use email for now.")
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val response = try {
            CredentialManager.create(activity).getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
        } catch (e: GetCredentialCancellationException) {
            return@guard Result.Error("")
        } catch (e: NoCredentialException) {
            return@guard Result.Error("No Google account on this phone. Add one in Settings › Accounts, or use email.")
        }
        val cred = response.credential
        if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return@guard Result.Error("Google didn't return an account. Try again.")
        }
        val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
        signInWith(GoogleAuthProvider.getCredential(token, null))
        Result.Ok
    }

    /** Keeps the anonymous id when the Google account is new to Rodeo; otherwise switches to the existing account. */
    private suspend fun signInWith(credential: AuthCredential) {
        val a = auth!!
        val current = a.currentUser
        if (current != null && current.isAnonymous) {
            try {
                current.linkWithCredential(credential).await()
                refresh()
                return
            } catch (e: FirebaseAuthUserCollisionException) {
                // That Google account already has a Rodeo account: sign into it instead.
            }
        }
        a.signInWithCredential(credential).await()
        refresh()
    }

    /** Signs out, and makes Android forget the Google choice so the account picker shows next time. */
    suspend fun signOut(activity: Activity? = null) {
        auth?.signOut()
        if (activity != null) {
            runCatching { CredentialManager.create(activity).clearCredentialState(androidx.credentials.ClearCredentialStateRequest()) }
        }
        refresh()
    }

    /** Deletes the account for good (Play Store rule). Needs a recent sign-in. */
    suspend fun delete(): Result = guard {
        auth!!.currentUser?.delete()?.await()
        refresh()
        Result.Ok
    }

    private inline fun guard(block: () -> Result): Result {
        if (auth == null) return Result.Error("Accounts aren't set up in this build")
        return try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "auth", e)
            Result.Error(message(e))
        }
    }

    companion object {
        private const val TAG = "Accounts"

        fun message(e: Exception): String = when (e) {
            is FirebaseNetworkException -> "No internet. Signing in needs a connection the first time."
            is FirebaseTooManyRequestsException -> "Too many tries. Wait a few minutes and try again."
            is FirebaseAuthWeakPasswordException -> "Use a password of at least 6 characters."
            is FirebaseAuthUserCollisionException -> "That email already has an account. Sign in instead."
            is FirebaseAuthInvalidUserException -> "No account with that email. Create one instead."
            is FirebaseAuthRecentLoginRequiredException -> "For safety, sign out and sign in again, then delete the account."
            is FirebaseAuthInvalidCredentialsException ->
                if ("email" in (e.message ?: "").lowercase() && "badly formatted" in (e.message ?: "")) "That email address doesn't look right."
                else "Wrong email or password."
            else -> (e.message ?: "Something went wrong").let {
                if ("CONFIGURATION_NOT_FOUND" in it || "OPERATION_NOT_ALLOWED" in it) "Sign-in isn't switched on for this app yet." else it
            }
        }

        /** Basic email shape check before calling the server. */
        fun looksLikeEmail(s: String) = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$").matches(s.trim())
    }
}
