package app.mittyfin.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.mittyfin.MittyfinApp
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val app = MittyfinApp.instance
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { server = app.prefs.lastServer() ?: "http://192.168.1.10:8096" }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Mittyfin", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Вход на сервер Jellyfin",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = server, onValueChange = { server = it }, label = { Text("Адрес сервера") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = user, onValueChange = { user = it }, label = { Text("Пользователь") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Пароль") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    runCatching { app.jellyfin.login(server, user.trim(), password) }
                        .onSuccess { onSignedIn() }
                        .onFailure { error = "Не удалось войти: ${it.message}" }
                    busy = false
                }
            },
            enabled = !busy && server.isNotBlank() && user.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(22.dp)) else Text("Войти")
        }
    }
}
