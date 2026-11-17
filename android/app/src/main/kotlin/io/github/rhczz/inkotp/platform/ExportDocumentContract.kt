package io.github.rhczz.inkotp.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

data class ExportDocumentRequest(val filename: String, val mimeType: String)

/** SAF owns placement and grants; no public working directory or broad storage permission. */
class ExportDocumentContract : ActivityResultContract<ExportDocumentRequest, Uri?>() {
    override fun createIntent(context: Context, input: ExportDocumentRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType).putExtra(Intent.EXTRA_TITLE, input.filename)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
