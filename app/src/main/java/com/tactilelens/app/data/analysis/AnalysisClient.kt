package com.tactilelens.app.data.analysis

import android.net.Uri
import com.tactilelens.app.data.model.AnalysisResult

/**
 * Vision-to-axes contract surface. The ML team's API lives behind this.
 *
 * Today this is fulfilled by [MockAnalysisClient]; replacement with a real
 * client (TFLite, on-device, or remote) is a swap of the [AppContainer]
 * binding. UI and renderers don't change.
 */
interface AnalysisClient {
    suspend fun analyze(uri: Uri): AnalysisResult
}
