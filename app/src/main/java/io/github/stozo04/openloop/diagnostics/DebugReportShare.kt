package io.github.stozo04.openloop.diagnostics

import android.content.Context
import android.content.Intent

/**
 * Launch the system share sheet with a plain-text diagnostic [report] (the tester-friendly
 * "Send debug report" path). Single implementation shared by the editor's reverse-failure overlay
 * and MainActivity's preview/save failure snackbars (reverse-output-validation spec §5.7) — the
 * intent shape previously lived inline at each call site.
 */
fun Context.shareDebugReport(report: String, subject: String, chooserTitle: String) {
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, report)
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    startActivity(Intent.createChooser(share, chooserTitle))
}
