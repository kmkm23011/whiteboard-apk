package com.whiteboard.app

object RenderState {
    @Volatile var running: Boolean = false
    @Volatile var progress: Int = 0
    @Volatile var status: String = "Ready"
    @Volatile var outputPath: String = ""
    @Volatile var cancelRequested: Boolean = false

    fun reset() {
        running = false
        progress = 0
        status = "Ready"
        outputPath = ""
        cancelRequested = false
    }
}
