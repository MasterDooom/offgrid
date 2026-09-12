package com.offgrid.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** A newline-delimited JSON channel. All socket work is off the main thread. */
class SocketChannel {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var serverJob: Job? = null
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private val _incoming = MutableSharedFlow<Packet>(extraBufferCapacity = 32)
    val incoming: SharedFlow<Packet> = _incoming

    fun startServer(port: Int = PORT) {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch {
            runCatching { ServerSocket(port).use { server -> while (!server.isClosed) attach(server.accept()) } }
                .onFailure { Log.e(TAG, "Server stopped", it) }
        }
    }

    suspend fun connect(host: String, port: Int = PORT) = withContext(Dispatchers.IO) {
        val client = Socket()
        client.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        attach(client)
    }

    private fun attach(newSocket: Socket) {
        socket?.close()
        socket = newSocket
        writer = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream(), Charsets.UTF_8))
        scope.launch {
            runCatching {
                BufferedReader(InputStreamReader(newSocket.getInputStream(), Charsets.UTF_8)).useLines { lines ->
                    lines.forEach { _incoming.emit(Packet.fromJson(it)) }
                }
            }.onFailure { Log.w(TAG, "Socket reader ended", it) }
        }
    }

    suspend fun send(packet: Packet) = withContext(Dispatchers.IO) {
        checkNotNull(writer) { "No socket connection" }.apply { write(packet.toJson()); newLine(); flush() }
    }

    fun close() { writer = null; socket?.close(); socket = null; serverJob?.cancel(); serverJob = null }
    fun dispose() { close(); scope.cancel() }

    companion object { private const val TAG = "OffGridSocket"; private const val PORT = 8988; private const val CONNECT_TIMEOUT_MS = 8_000 }
}
