package com.rodeo.hud.ui.setup

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.rodeo.hud.core.Accounts
import com.rodeo.hud.graph
import com.rodeo.hud.ui.components.BrandWordmark
import com.rodeo.hud.ui.components.GhostButton
import com.rodeo.hud.ui.components.GlassCard
import com.rodeo.hud.ui.components.PrimaryButton
import com.rodeo.hud.ui.components.SectionLabel
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalType
import kotlinx.coroutines.launch

private enum class Mode { SIGN_IN, CREATE, FORGOT, VERIFY }

/**
 * The first screen: Continue with Google, or email and password (new account with an email
 * verification link, sign in, forgot password). The app opens once the account is confirmed.
 */
@Composable
fun SignInScreen() {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = LocalHud.current
    val scope = rememberCoroutineScope()
    val account by g.accounts.account.collectAsState()
    var mode by remember { mutableStateOf(if (account != null && account?.ready == false) Mode.VERIFY else Mode.SIGN_IN) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf(account?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun act(action: suspend () -> Accounts.Result) {
        if (busy) return
        busy = true
        error = null
        info = null
        scope.launch {
            when (val r = action()) {
                Accounts.Result.Ok -> Unit
                Accounts.Result.NeedsVerification -> { mode = Mode.VERIFY; password = "" }
                is Accounts.Result.Error -> error = r.message.ifBlank { null }
            }
            busy = false
        }
    }

    fun emailOk(): Boolean {
        if (!Accounts.looksLikeEmail(email)) { error = "Enter your email address"; return false }
        return true
    }

    Row(Modifier.fillMaxSize().background(c.background).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(0.9f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            BrandWordmark()
            Spacer(Modifier.size(12.dp))
            Text("Your ride, your pack, one account.", color = c.text, style = LocalType.current.headlineLg)
            Spacer(Modifier.size(8.dp))
            Text(
                "Sign in to ride in packs with friends and keep your account when you change phones. " +
                    "Your rides and SOS contacts stay on this phone.",
                color = c.textMuted,
            )
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).widthIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            GlassCard(Modifier.fillMaxWidth()) {
                when (mode) {
                    Mode.VERIFY -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.MarkEmailRead, null, tint = c.accent, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Confirm your email", color = c.text, style = LocalType.current.headlineMd)
                        }
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "We sent a link to ${account?.email ?: email}. Open it on any device, then come back and tap below. " +
                                "No email? Check spam or promotions.",
                            color = c.textMuted,
                        )
                        Spacer(Modifier.size(10.dp))
                        PrimaryButton("I've confirmed it", Modifier.fillMaxWidth().testTag("signin-verified"), enabled = !busy) {
                            act { g.accounts.checkVerified() }
                        }
                        Spacer(Modifier.size(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton("Send again", icon = Icons.Filled.Email) {
                                act { g.accounts.resendVerification().also { if (it !is Accounts.Result.Error) info = "Sent. It can take a minute." } }
                            }
                            GhostButton("Use another account") {
                                scope.launch { g.accounts.signOut(ctx as? Activity) }
                                mode = Mode.SIGN_IN
                            }
                        }
                    }
                    Mode.FORGOT -> {
                        Text("Reset your password", color = c.text, style = LocalType.current.headlineMd)
                        Text("We'll email you a link to choose a new one.", color = c.textMuted)
                        Spacer(Modifier.size(8.dp))
                        InputField(email, { email = it.trim() }, "Email", KeyboardType.Email, Icons.Filled.Email)
                        Spacer(Modifier.size(10.dp))
                        PrimaryButton("Send reset link", Modifier.fillMaxWidth(), enabled = !busy) {
                            if (emailOk()) act {
                                g.accounts.sendReset(email).also { if (it == Accounts.Result.Ok) { info = "Reset link sent to $email. Use it, then sign in here."; mode = Mode.SIGN_IN } }
                            }
                        }
                        Spacer(Modifier.size(4.dp))
                        TextLink("Back to sign in") { mode = Mode.SIGN_IN; error = null }
                    }
                    Mode.SIGN_IN, Mode.CREATE -> {
                        val creating = mode == Mode.CREATE
                        Text(if (creating) "Create your account" else "Sign in", color = c.text, style = LocalType.current.headlineMd)
                        Spacer(Modifier.size(8.dp))
                        // Google needs the web client id that a fresh google-services.json carries.
                        if (g.accounts.googleEnabled) {
                            PrimaryButton("Continue with Google", Modifier.fillMaxWidth().testTag("signin-google"), enabled = !busy, icon = Icons.Filled.AccountCircle) {
                                val activity = ctx as? Activity
                                if (activity != null) act { g.accounts.google(activity) }
                            }
                            Spacer(Modifier.size(4.dp))
                            SectionLabel("Or with email")
                        }
                        if (creating) InputField(name, { name = it.take(24) }, "Your name (shown to your pack)", KeyboardType.Text, Icons.Filled.AccountCircle)
                        InputField(email, { email = it.trim() }, "Email", KeyboardType.Email, Icons.Filled.Email)
                        PasswordField(password, { password = it }, if (creating) "Password (6+ characters)" else "Password")
                        Spacer(Modifier.size(4.dp))
                        GhostButton(if (creating) "Create account" else "Sign in", Modifier.fillMaxWidth().testTag("signin-email"), icon = Icons.AutoMirrored.Filled.Login) {
                            if (!emailOk()) return@GhostButton
                            if (password.length < 6) { error = "Use a password of at least 6 characters"; return@GhostButton }
                            if (creating) {
                                if (name.isNotBlank()) g.profile.update { it.copy(riderName = name.trim()) }
                                act { g.accounts.create(email, password, name) }
                            } else act { g.accounts.signIn(email, password) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextLink(if (creating) "I have an account" else "New here? Create an account") {
                                mode = if (creating) Mode.SIGN_IN else Mode.CREATE
                                error = null
                            }
                            if (!creating) TextLink("Forgot password?") { mode = Mode.FORGOT; error = null }
                        }
                    }
                }
                if (busy) {
                    Spacer(Modifier.size(8.dp))
                    CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterHorizontally), color = c.accent, strokeWidth = 3.dp)
                }
                error?.let {
                    Spacer(Modifier.size(8.dp))
                    Text(it, color = c.danger, style = LocalType.current.bodySm, modifier = Modifier.testTag("signin-error"))
                }
                info?.let {
                    Spacer(Modifier.size(8.dp))
                    Text(it, color = c.ok, style = LocalType.current.bodySm)
                }
            }
        }
    }
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    val c = LocalHud.current
    Text(
        text, color = c.accent, fontWeight = FontWeight.SemiBold, style = LocalType.current.bodySm,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
    )
}

@Composable
private fun InputField(value: String, onChange: (String) -> Unit, hint: String, keyboard: KeyboardType, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val c = LocalHud.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(c.glass)
            .border(1.dp, c.glassBorder, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(hint, color = c.textMuted)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = LocalType.current.bodyLg.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, hint: String) {
    val c = LocalHud.current
    var show by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(c.glass)
            .border(1.dp, c.glassBorder, RoundedCornerShape(14.dp)).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(hint, color = c.textMuted)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = LocalType.current.bodyLg.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(Modifier.size(48.dp).clickable { show = !show }, contentAlignment = Alignment.Center) {
            Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (show) "Hide password" else "Show password", tint = c.textMuted)
        }
    }
}
