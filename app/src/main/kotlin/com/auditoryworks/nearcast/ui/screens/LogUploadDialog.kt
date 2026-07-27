package com.auditoryworks.nearcast.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private const val DEFAULT_LOG_DESCRIPTION = "NearHub Cast logs"
private const val DEFAULT_LOG_EMAIL = "example@mail.com"

@Composable
fun LogUploadDialog(
    isUploading: Boolean,
    onDismiss: () -> Unit,
    onUpload: (email: String, description: String) -> Unit
) {
    var email by rememberSaveable { mutableStateOf(DEFAULT_LOG_EMAIL) }
    var description by rememberSaveable { mutableStateOf(DEFAULT_LOG_DESCRIPTION) }

    AlertDialog(
        onDismissRequest = {
            if (!isUploading) {
                onDismiss()
            }
        },
        title = { Text("Upload Logs") },
        text = {
            Column {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !isUploading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    minLines = 3,
                    enabled = !isUploading
                )

                if (isUploading) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Uploading logs...")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onUpload(email, description) },
                enabled = !isUploading
            ) {
                Text("Upload")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isUploading
            ) {
                Text("Cancel")
            }
        }
    )
}
