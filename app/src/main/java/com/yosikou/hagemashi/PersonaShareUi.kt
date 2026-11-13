package com.yosikou.hagemashi

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@Composable internal fun PersonaImportPreview(persona:Persona,enabled:Boolean,confirm:()->Unit,cancel:()->Unit) {
    SectionCard {
        Text(stringResource(R.string.persona_import_preview),style=MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.persona_import_notice))
        Text(stringResource(R.string.persona_name_input),style=MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(persona.name) }
        Text(stringResource(R.string.persona_prompt_input),style=MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(persona.prompt,style=MaterialTheme.typography.bodyLarge) }
        Button(onClick=confirm,enabled=enabled){Text(stringResource(R.string.persona_import_confirm))}
        OutlinedButton(onClick=cancel,enabled=enabled){Text(stringResource(R.string.persona_import_cancel))}
    }
}
@Composable internal fun PersonaShareFeedback(share:PersonaShareViewModel) {
    if(share.busy)Text(stringResource(R.string.persona_file_busy))
    share.failure?.let { Text(stringResource(personaFileErrorText(it)),color=MaterialTheme.colorScheme.error) }
    when(share.status) {
        PersonaShareStatus.EXPORTED->Text(stringResource(R.string.persona_export_done))
        PersonaShareStatus.IMPORTED->Text(stringResource(R.string.persona_import_done))
        PersonaShareStatus.CANCELLED->Text(stringResource(R.string.persona_file_cancelled))
        else->Unit
    }
}
internal fun personaFileErrorText(error:PersonaFileError):Int=when(error) {
    PersonaFileError.INVALID_JSON->R.string.persona_file_json_error
    PersonaFileError.UNSUPPORTED_VERSION->R.string.persona_file_version_error
    PersonaFileError.MISSING_FIELDS->R.string.persona_file_missing_error
    PersonaFileError.INVALID_FIELDS->R.string.persona_file_fields_error
    PersonaFileError.TOO_LARGE->R.string.persona_file_size_error
    PersonaFileError.READ_FAILED->R.string.persona_file_read_error
    PersonaFileError.WRITE_FAILED->R.string.persona_file_write_error
    PersonaFileError.SAVE_FAILED->R.string.persona_file_save_error
}
