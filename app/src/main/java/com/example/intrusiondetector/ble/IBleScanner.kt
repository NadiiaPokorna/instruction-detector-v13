package com.example.intrusiondetector.ble

import kotlinx.coroutines.flow.Flow

interface IBleScanner {
    /** Холодний потік: поки його збирають — триває сканування. */
    fun scan(timeoutMs: Long = 6_000): Flow<ScanEvent>
}
