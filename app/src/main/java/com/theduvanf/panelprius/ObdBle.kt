@file:Suppress("DEPRECATION")

package com.theduvanf.panelprius

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Talks to an ELM327-compatible OBD-II adapter over Bluetooth LE and
 * pushes speed, RPM, throttle, coolant and hybrid-battery readings to the panel.
 */
@SuppressLint("MissingPermission")
class ObdBle(private val ctx: Context, val listener: Listener) {

    interface Listener {
        fun onDevice(name: String, address: String)
        fun onStatus(text: String, live: Boolean)
        fun onData(data: JSONObject)
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var rx: BluetoothGattCharacteristic? = null
    private val responses = LinkedBlockingQueue<String>()
    private val writeDone = Semaphore(0)
    private val buffer = StringBuilder()
    @Volatile private var running = false
    @Volatile var isLive = false
        private set
    private var scanning = false

    private val preferredServices = listOf("fff0", "ffe0", "18f0", "e7810a71")
    private val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ---------- scanning ----------
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val name = d.name ?: result.scanRecord?.deviceName ?: return
            listener.onDevice(name, d.address)
        }

        override fun onScanFailed(errorCode: Int) {
            listener.onStatus("No se pudo buscar dispositivos (error $errorCode). Apaga y prende el Bluetooth e intenta de nuevo.", false)
        }
    }

    fun scan() {
        val a = adapter
        if (a == null) { listener.onStatus("Esta tablet no tiene Bluetooth LE.", false); return }
        if (!a.isEnabled) { listener.onStatus("Activa el Bluetooth de la tablet y vuelve a tocar Conectar.", false); return }
        // Adapters already paired show up even if they are not advertising right now.
        a.bondedDevices?.forEach { d -> d.name?.let { listener.onDevice(it, d.address) } }
        val scanner = a.bluetoothLeScanner ?: return
        if (scanning) scanner.stopScan(scanCb)
        scanning = true
        scanner.startScan(scanCb)
        main.postDelayed({ stopScan() }, 15000)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    // ---------- connection ----------
    fun connect(address: String) {
        stopScan()
        disconnect()
        val device: BluetoothDevice = adapter?.getRemoteDevice(address) ?: return
        listener.onStatus("Conectando…", false)
        gatt = device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        running = false
        isLive = false
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null; tx = null; rx = null
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasRunning = running || isLive
                running = false; isLive = false
                try { g.close() } catch (_: Exception) {}
                if (gatt == g) gatt = null
                listener.onStatus(
                    if (wasRunning) "Se perdió la conexión con el adaptador. Toca Conectar para reintentar."
                    else "No se pudo conectar. Revisa que el carro esté encendido y que el adaptador no esté conectado a otro teléfono.",
                    false
                )
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val services = g.services.sortedBy { s ->
                val id = s.uuid.toString().lowercase()
                if (preferredServices.any { id.contains(it) }) 0 else 1
            }
            var w: BluetoothGattCharacteristic? = null
            var r: BluetoothGattCharacteristic? = null
            for (s in services) {
                for (c in s.characteristics) {
                    val p = c.properties
                    if (r == null && (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) r = c
                    if (w == null && (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) w = c
                }
                if (w != null && r != null) break
            }
            if (w == null || r == null) {
                listener.onStatus("Este adaptador no tiene un canal de datos compatible.", false)
                g.disconnect(); return
            }
            tx = w; rx = r
            g.setCharacteristicNotification(r, true)
            val desc = r.getDescriptor(cccd)
            if (desc == null) { startWorker(); return }
            val value = if ((r.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(desc, value)
            } else {
                desc.value = value
                g.writeDescriptor(desc)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            startWorker()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone.release()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            received(c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            received(value)
        }
    }

    private fun received(bytes: ByteArray) {
        synchronized(buffer) {
            buffer.append(String(bytes, Charsets.US_ASCII))
            if (buffer.contains('>')) {
                responses.offer(buffer.toString())
                buffer.setLength(0)
            }
        }
    }

    private fun write(cmd: String) {
        val g = gatt ?: return
        val c = tx ?: return
        val bytes = (cmd + "\r").toByteArray(Charsets.US_ASCII)
        val type = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        writeDone.drainPermits()
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, bytes, type)
        } else {
            c.writeType = type
            c.value = bytes
            g.writeCharacteristic(c)
        }
        writeDone.tryAcquire(500, TimeUnit.MILLISECONDS)
    }

    private fun send(cmd: String, timeoutMs: Long = 1500): String {
        responses.clear()
        synchronized(buffer) { buffer.setLength(0) }
        write(cmd)
        return responses.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: ""
    }

    private fun parse(resp: String, pid: String): IntArray? {
        val s = resp.replace(Regex("[\\s>]"), "").uppercase()
        val i = s.indexOf("41$pid")
        if (i < 0) return null
        val hex = s.substring(i + 4)
        val n = hex.length / 2
        if (n == 0) return null
        return IntArray(n) { hex.substring(it * 2, it * 2 + 2).toIntOrNull(16) ?: 0 }
    }

    private fun startWorker() {
        if (running) return
        running = true
        Thread {
            try {
                send("ATZ", 2500)
                for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) send(c)
                send("0100", 6000) // lets the adapter find the car's protocol
                isLive = true
                listener.onStatus("Conectado a tu Prius.", true)
                var n = 0
                while (running) {
                    val d = JSONObject()
                    parse(send("010D"), "0D")?.let { d.put("speed", it[0]) }
                    parse(send("010C"), "0C")?.let { if (it.size >= 2) d.put("rpm", (it[0] * 256 + it[1]) / 4.0) }
                    parse(send("0111"), "11")?.let { d.put("thr", it[0] * 100.0 / 255) }
                    if (n++ % 5 == 0) {
                        parse(send("0105"), "05")?.let { d.put("cool", it[0] - 40) }
                        parse(send("015B"), "5B")?.let { d.put("hv", it[0] * 100.0 / 255) }
                    }
                    if (d.length() > 0) listener.onData(d)
                }
            } catch (e: Exception) {
                running = false
                isLive = false
                listener.onStatus("Error con el adaptador: ${e.message}", false)
            }
        }.start()
    }
}
