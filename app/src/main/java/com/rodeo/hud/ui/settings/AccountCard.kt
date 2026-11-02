package com.rodeo.hud.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rodeo.hud.core.Accounts
import com.rodeo.hud.graph
import com.rodeo.hud.ui.components.GhostButton
import com.rodeo.hud.ui.components.GlassCard
import com.rodeo.hud.ui.components.SectionLabel
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalType
import kotlinx.coroutines.launch

/** Settings › Account: who is signed in, sign out, and delete the account (a Play Store rule). */
@Composable
fun AccountCard() {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = LocalHud.current
    val account by g.accounts.account.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    if (!g.accounts.enabled) return
    GlassCard(Modifier.fillMaxWidth()) {
        SectionLabel("Account")
        val a = account
        Text(
            if (a == null) "Not signed in" else listOfNotNull(a.name, a.email).joinToString(" · ") + if (a.google) " (Google)" else "",
            color = c.text, modifier = Modifier.padding(vertical = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Sign out", icon = Icons.AutoMirrored.Filled.Logout) {
                g.pack.leave()
                scope.launch { g.accounts.signOut(ctx as? Activity) }
            }
            GhostButton("Delete account", icon = Icons.Filled.DeleteForever) { confirmDelete = true }
        }
        note?.let { Text(it, color = c.warning, style = LocalType.current.bodySm) }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = c.surface, titleContentColor = c.text, textContentColor = c.textMuted,
            title = { Text("Delete your Rodeo account?") },
            text = { Text("Your account and sign-in are deleted for good, and you leave any pack. Rides, garage and SOS contacts saved on this phone stay until you uninstall the app.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    g.pack.leave()
                    scope.launch {
                        when (val r = g.accounts.delete()) {
                            is Accounts.Result.Error -> note = r.message
                            else -> note = null
                        }
                    }
                }) { Text("Delete", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it", color = c.text) } },
        )
    }
}
