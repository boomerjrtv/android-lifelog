package com.lifelog.phone.presentation.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class GoogleAuthResult(
    val success: Boolean,
    val token: String? = null,
    val email: String? = null,
    val name: String? = null,
    val error: String? = null
)

@Singleton
class GoogleSignInService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestProfile()
        .requestIdToken("27950714813-jitkigtgp0f0ihqp6q2vfujnpo9qmckm.apps.googleusercontent.com")
        .build()
    
    private val googleSignInClient = GoogleSignIn.getClient(context, gso)

    fun getSignInIntent(): Intent = googleSignInClient.signInIntent
    
    suspend fun signIn(): GoogleAuthResult {
        return try {
            val signInIntent = googleSignInClient.signInIntent
            // This would typically be launched from an Activity
            // For now, return a placeholder result
            GoogleAuthResult(
                success = false,
                error = "Sign-in intent needs to be launched from Activity"
            )
        } catch (e: ApiException) {
            Log.w("GoogleSignIn", "Google sign in failed", e)
            GoogleAuthResult(
                success = false,
                error = e.localizedMessage
            )
        } catch (e: Exception) {
            Log.e("GoogleSignIn", "Unexpected error during sign in", e)
            GoogleAuthResult(
                success = false,
                error = e.localizedMessage
            )
        }
    }
    
    fun getSignedInAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }
    
    fun signOut() {
        googleSignInClient.signOut()
    }
    
    fun handleSignInResult(data: Intent?): GoogleAuthResult {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            GoogleAuthResult(
                success = true,
                token = account.idToken,
                email = account.email,
                name = account.displayName
            )
        } catch (e: ApiException) {
            Log.w("GoogleSignIn", "Google sign in failed", e)
            GoogleAuthResult(
                success = false,
                error = e.localizedMessage
            )
        } catch (e: Exception) {
            Log.e("GoogleSignIn", "Unexpected error", e)
            GoogleAuthResult(
                success = false,
                error = e.localizedMessage
            )
        }
    }
}
