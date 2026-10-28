package com.example.appscrip_live_stream_component.deepar

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.MediaStream
import org.webrtc.YuvHelper
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.HashMap
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Creates a WebRTC video track backed by app-pushed RGBA/BGRA frames (DeepAR).
 *
 * Frame conversion runs on a background thread; the method-channel call returns
 * immediately so the Flutter UI / platform thread is not blocked (hang fix).
 *
 * ponytail: reflection into flutter_webrtc internals — replace with public
 * CustomVideoSource API when flutter-webrtc PR #2094 merges.
 * Release R8 renames those members; consumer-rules.pro keeps the names, and
 * the lookups below also match by type when a host shrink config drops them.
 */
class ExternalVideoTrackManager {
  companion object {
    private const val TAG = "IsmLiveExtVideo"
  }

  private var videoSource: VideoSource? = null
  private var videoTrack: VideoTrack? = null
  private var mediaStream: MediaStream? = null
  private var streamId: String? = null
  private var trackId: String? = null
  private var capturerStarted = false
  private val pushing = AtomicBoolean(false)
  private var webrtcHandler: Any? = null
  private var rgbaScratch: ByteBuffer? = null
  private var libyuvFailed = false

  private var frameThread: HandlerThread? = null
  private var frameHandler: Handler? = null

  // ponytail: sharedSingleton is process-global. Use it whenever it already has
  // a factory (single-engine hosts). Only if it does not, ask the engine that
  // received this call — a later background engine can overwrite the singleton.
  // The lookup returns the FlutterPluginBinding, not the WebRTC plugin.
  private var engineBindingLookup: (() -> Any?)? = null

  fun bindEngineLookup(lookup: (() -> Any?)?) {
    engineBindingLookup = lookup
  }

  fun createTrack(width: Int, height: Int, result: MethodChannel.Result) {
    try {
      disposeInternal()
      ensureFrameThread()

      val plugin = resolveFlutterWebRtcPlugin()
        ?: throw IllegalStateException("FlutterWebRTCPlugin not ready")
      val factory = peerConnectionFactoryOf(plugin)
        ?: throw IllegalStateException("PeerConnectionFactory is null — connect LiveKit first")
      Log.i("FlutterDeepAR", "WebRTC factory ready")

      val handler = resolveHandler(plugin)
      webrtcHandler = handler

      val sid = UUID.randomUUID().toString()
      val tid = UUID.randomUUID().toString()

      val source = factory.createVideoSource(/* isScreencast= */ false)
      val track = factory.createVideoTrack(tid, source)
      track.setEnabled(true)
      val stream = factory.createLocalMediaStream(sid)
      stream.addTrack(track)

      source.capturerObserver.onCapturerStarted(true)
      capturerStarted = true
      // Own the objects before the reflective register. If that throws (R8
      // renamed LocalVideoTrack), disposeInternal must still release them —
      // a leaked started source breaks the next camera offer's m-line order.
      videoSource = source
      videoTrack = track
      mediaStream = stream
      streamId = sid
      trackId = tid

      // Register with flutter_webrtc. Do NOT setVideoProcessor — push frames
      // straight into capturerObserver so they reach the encoder/renderer.
      val localVideoTrack = wrapVideoTrack(track, handler)
      invoke(handler, "putLocalStream", sid, stream, allowRename = true)
      invoke(handler, "putLocalTrack", tid, localVideoTrack, allowRename = true)

      val videoTrackMap = HashMap<String, Any?>()
      videoTrackMap["id"] = tid
      videoTrackMap["label"] = tid
      videoTrackMap["kind"] = "video"
      videoTrackMap["enabled"] = true
      videoTrackMap["readyState"] = "live"
      videoTrackMap["remote"] = false
      videoTrackMap["settings"] = HashMap<String, Any>()

      val videoTracks = ArrayList<Any>()
      videoTracks.add(videoTrackMap)

      val response = HashMap<String, Any?>()
      response["streamId"] = sid
      response["audioTracks"] = ArrayList<Any>()
      response["videoTracks"] = videoTracks
      Log.d(TAG, "Created external video track $tid on stream $sid (${width}x$height)")
      result.success(response)
    } catch (t: Throwable) {
      Log.e(TAG, "createTrack failed", t)
      // Same tag the host filters on, so the reason sits next to "pipeline destroyed".
      Log.e(
        "FlutterDeepAR",
        "external video track failed: ${t.javaClass.simpleName}: ${t.message}",
        t,
      )
      disposeInternal()
      result.error("createExternalVideoTrack", t.message, null)
    }
  }

  fun pushFrame(
    data: ByteArray,
    width: Int,
    height: Int,
    format: String,
    timestampMs: Long,
    rotation: Int,
    mirror: Boolean,
    result: MethodChannel.Result,
  ) {
    val source = videoSource
    if (source == null) {
      result.error("pushExternalVideoFrame", "No active external track", null)
      return
    }
    // Ack immediately — never block the platform thread on RGBA→I420.
    result.success(null)

    if (!pushing.compareAndSet(false, true)) {
      return
    }
    val handler = frameHandler
    if (handler == null) {
      pushing.set(false)
      return
    }
    // Copy is already owned by the method-channel buffer; post work off-thread.
    handler.post {
      try {
        val buffer = JavaI420Buffer.allocate(width, height)
        convertToI420(data, width, height, format, mirror, buffer)
        val rot = when (rotation) {
          90, 180, 270 -> rotation
          else -> 0
        }
        val tsNs = if (timestampMs > 0) {
          timestampMs * 1_000_000L
        } else {
          System.nanoTime()
        }
        val frame = VideoFrame(buffer, rot, tsNs)
        source.capturerObserver.onFrameCaptured(frame)
        frame.release()
      } catch (t: Throwable) {
        Log.e(TAG, "pushFrame failed", t)
      } finally {
        pushing.set(false)
      }
    }
  }

  fun dispose(result: MethodChannel.Result) {
    disposeInternal()
    result.success(null)
  }

  private fun ensureFrameThread() {
    if (frameThread != null) return
    frameThread = HandlerThread("IsmLiveExtVideo").also { it.start() }
    frameHandler = Handler(frameThread!!.looper)
  }

  private fun stopFrameThread() {
    frameThread?.quitSafely()
    try {
      frameThread?.join(500)
    } catch (_: InterruptedException) {
    }
    frameThread = null
    frameHandler = null
  }

  private fun disposeInternal() {
    try {
      if (capturerStarted) {
        videoSource?.capturerObserver?.onCapturerStopped()
      }
    } catch (_: Throwable) {
    }
    capturerStarted = false
    try {
      videoTrack?.setEnabled(false)
      videoTrack?.dispose()
    } catch (_: Throwable) {
    }
    videoTrack = null
    try {
      videoSource?.dispose()
    } catch (_: Throwable) {
    }
    videoSource = null
    try {
      mediaStream?.dispose()
    } catch (_: Throwable) {
    }
    mediaStream = null

    val handler = webrtcHandler
    val tid = trackId
    val sid = streamId
    if (handler != null && tid != null) {
      try {
        invoke(handler, "trackDispose", tid, allowRename = false)
      } catch (_: Throwable) {
      }
    }
    if (handler != null && sid != null) {
      try {
        invoke(handler, "streamDispose", sid, allowRename = false)
      } catch (_: Throwable) {
      }
    }
    webrtcHandler = null
    trackId = null
    streamId = null
    rgbaScratch = null
    stopFrameThread()
  }

  private fun resolveFlutterWebRtcPlugin(): Any? {
    // The engine that received this call owns the factory LiveKit initialized.
    // sharedSingleton is whatever FlutterWebRTCPlugin was constructed last, and
    // a later engine overwrites it with an instance whose factory is still null.
    val binding = try {
      engineBindingLookup?.invoke()
    } catch (t: Throwable) {
      Log.e(TAG, "FlutterWebRTCPlugin engine lookup failed", t)
      null
    }
    if (binding != null) {
      pluginFromBinding(binding)?.let { return it }
    }
    val singleton = pluginByName()
    if (singleton != null && peerConnectionFactoryOf(singleton) != null) {
      return singleton
    }
    return null
  }

  private fun pluginByName(): Any? {
    return try {
      val clazz = Class.forName("com.cloudwebrtc.webrtc.FlutterWebRTCPlugin")
      try {
        clazz.getField("sharedSingleton").get(null)
      } catch (_: Throwable) {
        clazz.declaredFields.firstOrNull { field ->
          Modifier.isStatic(field.modifiers) &&
            (field.type == clazz || clazz.isAssignableFrom(field.type))
        }?.apply { isAccessible = true }?.get(null)
      }
    } catch (t: Throwable) {
      Log.e(TAG, "FlutterWebRTCPlugin lookup failed", t)
      null
    }
  }

  /**
   * WebRTC plugin registered on this engine.
   * Release R8 renames getFlutterEngine / getPlugins, so the walk follows
   * fields and plugin maps instead of those names. sharedSingleton is not used
   * here: a second Flutter engine replaces it with an uninitialized plugin.
   */
  private fun pluginFromBinding(binding: Any): Any? {
    try {
      val webrtcClass = Class.forName("com.cloudwebrtc.webrtc.FlutterWebRTCPlugin")
      for (method in binding.javaClass.methods) {
        if (Modifier.isStatic(method.modifiers) || method.parameterTypes.size != 1) continue
        if (method.parameterTypes[0] != Class::class.java) continue
        val plugin = try {
          method.invoke(binding, webrtcClass)
        } catch (_: Throwable) {
          null
        } ?: continue
        if (peerConnectionFactoryOf(plugin) != null) return plugin
      }
    } catch (_: Throwable) {
    }
    return findPluginWithFactory(binding)
  }

  /** BFS from the binding until a plugin map holds an initialized factory. */
  private fun findPluginWithFactory(root: Any): Any? {
    val seen = IdentityHashMap<Any, Boolean>()
    val queue = ArrayDeque<Pair<Any, Int>>()
    queue.add(root to 0)
    var visited = 0
    while (queue.isNotEmpty() && visited < 400) {
      val (obj, depth) = queue.poll() ?: break
      if (seen.containsKey(obj)) continue
      seen[obj] = true
      visited++
      for (value in instanceValues(obj)) {
        if (value is Map<*, *>) {
          for (plugin in value.values) {
            if (plugin != null && peerConnectionFactoryOf(plugin) != null) return plugin
          }
          continue
        }
        if (depth < 4 && worthDescending(value)) {
          queue.add(value to depth + 1)
        }
      }
    }
    return null
  }

  private fun worthDescending(value: Any): Boolean {
    if (value is PeerConnectionFactory) return false
    val name = value.javaClass.name
    return !(name.startsWith("java.") ||
      name.startsWith("javax.") ||
      name.startsWith("android.") ||
      name.startsWith("androidx.") ||
      name.startsWith("kotlin.") ||
      name.startsWith("kotlinx.") ||
      name.startsWith("dalvik."))
  }

  private fun peerConnectionFactoryOf(owner: Any): PeerConnectionFactory? {
    if (owner is PeerConnectionFactory) return null
    // Name first (debug). Release R8 renames the getter but keeps the return type.
    val getters = ArrayList<Method>()
    var cls: Class<*>? = owner.javaClass
    while (cls != null && cls != Any::class.java) {
      for (method in cls.declaredMethods) {
        if (Modifier.isStatic(method.modifiers) || method.parameterTypes.isNotEmpty()) continue
        if (PeerConnectionFactory::class.java.isAssignableFrom(method.returnType)) {
          getters.add(method)
        }
      }
      cls = cls.superclass
    }
    getters.sortByDescending { it.name == "getPeerConnectionFactory" }
    for (method in getters) {
      val factory = try {
        method.isAccessible = true
        method.invoke(owner) as? PeerConnectionFactory
      } catch (_: Throwable) {
        null
      }
      if (factory != null) return factory
    }
    return try {
      findFactory(owner, 0, IdentityHashMap())
    } catch (_: Throwable) {
      null
    }
  }

  private fun findFactory(
    obj: Any?,
    depth: Int,
    seen: IdentityHashMap<Any, Boolean>,
  ): PeerConnectionFactory? {
    if (obj == null || depth > 1 || seen.containsKey(obj)) return null
    seen[obj] = true
    val typeName = obj.javaClass.name
    if (depth > 0 &&
      (typeName.startsWith("java.") ||
        typeName.startsWith("android.") ||
        typeName.startsWith("androidx.") ||
        typeName.startsWith("kotlin."))
    ) {
      return null
    }
    for (field in instanceFields(obj.javaClass)) {
      if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
      field.isAccessible = true
      val value = try {
        field.get(obj)
      } catch (_: Throwable) {
        null
      } ?: continue
      if (value is PeerConnectionFactory) return value
      if (depth < 1) {
        findFactory(value, depth + 1, seen)?.let { return it }
      }
    }
    return null
  }

  private fun instanceFields(type: Class<*>): List<Field> {
    val fields = ArrayList<Field>()
    var cls: Class<*>? = type
    while (cls != null && cls != Any::class.java) {
      fields.addAll(cls.declaredFields)
      cls = cls.superclass
    }
    return fields
  }

  private fun instanceValues(obj: Any): List<Any> {
    val values = ArrayList<Any>()
    for (field in instanceFields(obj.javaClass)) {
      if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
      field.isAccessible = true
      val value = try {
        field.get(obj)
      } catch (_: Throwable) {
        null
      } ?: continue
      values.add(value)
    }
    return values
  }

  private fun resolveHandler(plugin: Any): Any {
    try {
      val field = plugin.javaClass.getDeclaredField("methodCallHandler")
      field.isAccessible = true
      field.get(plugin)?.let { return it }
    } catch (_: Throwable) {
    }
    for (field in plugin.javaClass.declaredFields) {
      if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
      if (PeerConnectionFactory::class.java.isAssignableFrom(field.type)) continue
      field.isAccessible = true
      val value = try {
        field.get(plugin)
      } catch (_: Throwable) {
        null
      } ?: continue
      if (hasDirectFactory(value)) return value
    }
    throw IllegalStateException("methodCallHandler is null")
  }

  private fun hasDirectFactory(obj: Any): Boolean {
    return obj.javaClass.declaredFields.any { field ->
      PeerConnectionFactory::class.java.isAssignableFrom(field.type)
    }
  }

  /**
   * LocalVideoTrack by name when R8 kept it. Otherwise the handler method
   * parameter that can be constructed from a [VideoTrack] (LocalTrack).
   * Class.forName fails once release shrink renames the class.
   */
  private fun wrapVideoTrack(track: VideoTrack, handler: Any): Any {
    try {
      val cls = Class.forName("com.cloudwebrtc.webrtc.video.LocalVideoTrack")
      val ctor = cls.getConstructor(VideoTrack::class.java)
      ctor.isAccessible = true
      return ctor.newInstance(track)
    } catch (t: Throwable) {
      Log.w(TAG, "LocalVideoTrack by name failed, using handler parameter type", t)
    }
    var best: Class<*>? = null
    var bestDepth = -1
    val methods = handler.javaClass.methods.asList() + handler.javaClass.declaredMethods.asList()
    for (method in methods) {
      if (method.parameterTypes.size != 2 || method.parameterTypes[0] != String::class.java) {
        continue
      }
      val param = method.parameterTypes[1]
      val matches = param.declaredConstructors.any { c ->
        c.parameterTypes.size == 1 && c.parameterTypes[0].isAssignableFrom(track.javaClass)
      }
      if (!matches) continue
      val depth = inheritanceDepth(param)
      if (depth > bestDepth) {
        bestDepth = depth
        best = param
      }
    }
    val cls = best ?: throw IllegalStateException("WebRTC local-track type not found")
    val ctor = cls.declaredConstructors.first { c ->
      c.parameterTypes.size == 1 && c.parameterTypes[0].isAssignableFrom(track.javaClass)
    }
    ctor.isAccessible = true
    return ctor.newInstance(track)
  }

  private fun inheritanceDepth(type: Class<*>): Int {
    var depth = 0
    var current: Class<*>? = type
    while (current != null && current != Any::class.java) {
      depth++
      current = current.superclass
    }
    return depth
  }

  /**
   * Prefer the real method name. [allowRename] is only for register calls:
   * dispose names are ambiguous (many one-String methods) once R8 renames them.
   */
  private fun invoke(target: Any, name: String, vararg args: Any?, allowRename: Boolean) {
    val method = findInvokable(target, name, args, allowRename)
    method.isAccessible = true
    method.invoke(target, *args)
  }

  private fun findInvokable(
    target: Any,
    name: String,
    args: Array<out Any?>,
    allowRename: Boolean,
  ): Method {
    val all = LinkedHashSet<Method>()
    all.addAll(target.javaClass.methods)
    all.addAll(target.javaClass.declaredMethods)
    fun accepts(m: Method): Boolean {
      if (m.parameterTypes.size != args.size) return false
      for (i in args.indices) {
        val arg = args[i] ?: continue
        if (!m.parameterTypes[i].isAssignableFrom(arg.javaClass)) return false
      }
      return true
    }
    all.firstOrNull { it.name == name && accepts(it) }?.let { return it }
    if (!allowRename) throw NoSuchMethodException(name)
    val fallback = all.filter { method ->
      accepts(method) && method.parameterTypes.none { it == Object::class.java }
    }
    if (fallback.isEmpty()) throw NoSuchMethodException(name)
    return fallback.maxBy { method ->
      method.parameterTypes.sumOf { inheritanceDepth(it) }
    }
  }

  private fun convertRgbaWithLibyuv(
    src: ByteArray,
    width: Int,
    height: Int,
    buffer: JavaI420Buffer,
  ) {
    val needed = width * height * 4
    if (src.size < needed) {
      throw IllegalArgumentException("rgba frame smaller than ${width}x$height")
    }
    var direct = rgbaScratch
    if (direct == null || direct.capacity() < needed) {
      direct = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
      rgbaScratch = direct
    }
    direct.clear()
    direct.put(src, 0, needed)
    direct.flip()
    YuvHelper.ABGRToI420(
      direct,
      width * 4,
      buffer.dataY,
      buffer.strideY,
      buffer.dataU,
      buffer.strideU,
      buffer.dataV,
      buffer.strideV,
      width,
      height,
    )
  }

  private fun convertToI420(
    src: ByteArray,
    width: Int,
    height: Int,
    format: String,
    mirror: Boolean,
    buffer: JavaI420Buffer,
  ) {
    // Android DeepAR frames are RGBA, which is libyuv ABGR in memory.
    // mirror stays on the scalar path; the publisher does not set it.
    if (!mirror && !libyuvFailed && format.equals("rgba", ignoreCase = true)) {
      try {
        convertRgbaWithLibyuv(src, width, height, buffer)
        return
      } catch (t: Throwable) {
        libyuvFailed = true
        Log.w(TAG, "libyuv ABGRToI420 failed, using scalar convert", t)
      }
    }

    val isBgra = format.equals("bgra", ignoreCase = true)
    val y = buffer.dataY
    val u = buffer.dataU
    val v = buffer.dataV
    val yStride = buffer.strideY
    val uStride = buffer.strideU
    val vStride = buffer.strideV

    for (row in 0 until height) {
      val yRow = row * yStride
      for (col in 0 until width) {
        val srcCol = if (mirror) (width - 1 - col) else col
        val srcIndex = (row * width + srcCol) * 4
        val b0 = src[srcIndex].toInt() and 0xff
        val b1 = src[srcIndex + 1].toInt() and 0xff
        val b2 = src[srcIndex + 2].toInt() and 0xff
        val r: Int
        val g: Int
        val b: Int
        if (isBgra) {
          b = b0
          g = b1
          r = b2
        } else {
          r = b0
          g = b1
          b = b2
        }
        val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
        y.put(yRow + col, yVal.coerceIn(0, 255).toByte())
      }
    }

    for (row in 0 until height step 2) {
      val uRow = (row / 2) * uStride
      val vRow = (row / 2) * vStride
      for (col in 0 until width step 2) {
        var rSum = 0
        var gSum = 0
        var bSum = 0
        var count = 0
        for (dy in 0..1) {
          val rr = row + dy
          if (rr >= height) continue
          for (dx in 0..1) {
            val cc = col + dx
            if (cc >= width) continue
            val srcCol = if (mirror) (width - 1 - cc) else cc
            val idx = (rr * width + srcCol) * 4
            val b0 = src[idx].toInt() and 0xff
            val b1 = src[idx + 1].toInt() and 0xff
            val b2 = src[idx + 2].toInt() and 0xff
            if (isBgra) {
              bSum += b0
              gSum += b1
              rSum += b2
            } else {
              rSum += b0
              gSum += b1
              bSum += b2
            }
            count++
          }
        }
        if (count == 0) continue
        val r = rSum / count
        val g = gSum / count
        val b = bSum / count
        val uVal = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
        val vVal = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
        u.put(uRow + col / 2, uVal.coerceIn(0, 255).toByte())
        v.put(vRow + col / 2, vVal.coerceIn(0, 255).toByte())
      }
    }
  }
}
