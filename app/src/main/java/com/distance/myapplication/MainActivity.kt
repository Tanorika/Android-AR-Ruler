package com.distance.myapplication

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.distance.myapplication.ui.theme.MyApplicationTheme
import com.google.ar.core.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    private var session: Session? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                var arInstalled by remember { mutableStateOf<Boolean?>(null) }
                val context = LocalContext.current

                LaunchedEffect(Unit) {
                    val status = ArCoreApk.getInstance().checkAvailability(context)
                    arInstalled = isPackageInstalled(context, "com.google.ar.core") && 
                                 status == ArCoreApk.Availability.SUPPORTED_INSTALLED
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (arInstalled) {
                        true -> ARMeasureScreen()
                        false -> ARCheckScreen(onInstalled = { arInstalled = true })
                        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
    }
}

@Composable
fun ARCheckScreen(onInstalled: () -> Unit) {
    val context = LocalContext.current
    var isInstalling by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (isPackageInstalled(context, "com.google.ar.core") && 
                    ArCoreApk.getInstance().checkAvailability(context) == ArCoreApk.Availability.SUPPORTED_INSTALLED) {
                    onInstalled()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("需要安装 AR 框架", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text("测量功能需要 Google ARCore 支持，请安装后继续。", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            if (isInstalling) CircularProgressIndicator() else InstallButton(context) { isInstalling = it }
        }
    }
}

@Composable
fun ARMeasureScreen() {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }

    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.CAMERA) }

    if (!hasPermission) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("授权相机权限") }
        }
        return
    }

    var anchors by remember { mutableStateOf(listOf<Anchor>()) }
    var trackingMsg by remember { mutableStateOf("正在初始化...") }
    var currentHit by remember { mutableStateOf<HitResult?>(null) }
    var session by remember { mutableStateOf<Session?>(null) }
    var lastFrame by remember { mutableStateOf<Frame?>(null) }
    var viewWidth by remember { mutableIntStateOf(1) }
    var viewHeight by remember { mutableIntStateOf(1) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val s = Session(context).apply {
            val config = Config(this)
            config.focusMode = Config.FocusMode.AUTO
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            configure(config)
        }
        session = s
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) s.resume()
            else if (event == Lifecycle.Event.ON_PAUSE) s.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); s.close() }
    }

    Box(Modifier.fillMaxSize()) {
        val s = session
        if (s != null) {
            AndroidView(
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(2)
                        setRenderer(object : GLSurfaceView.Renderer {
                            private var backgroundRenderer: BackgroundRenderer? = null
                            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                                backgroundRenderer = BackgroundRenderer().apply { createOnGlThread() }
                                s.setCameraTextureName(backgroundRenderer!!.textureId)
                            }
                            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                                viewWidth = width; viewHeight = height
                                GLES20.glViewport(0, 0, width, height)
                                val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                                val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ctx.display else windowManager.defaultDisplay
                                s.setDisplayGeometry(display?.rotation ?: 0, width, height)
                            }
                            override fun onDrawFrame(gl: GL10?) {
                                try {
                                    val frame = s.update()
                                    lastFrame = frame
                                    backgroundRenderer?.draw(frame)
                                    
                                    val hits = frame.hitTest(viewWidth / 2f, viewHeight / 2f)
                                    val bestHit = hits.firstOrNull { it.trackable is Plane } 
                                                 ?: hits.firstOrNull { it.trackable is Point && (it.trackable as Point).orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL }
                                    currentHit = bestHit
                                    
                                    trackingMsg = if (frame.camera.trackingState == TrackingState.TRACKING) {
                                        if (bestHit != null) "锁定成功" else "请移动以寻找平面"
                                    } else "AR 初始化中..."
                                } catch (e: Exception) {}
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Canvas(Modifier.fillMaxSize()) {
            val frame = lastFrame ?: return@Canvas
            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) return@Canvas

            val viewMatrix = FloatArray(16)
            val projMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)

            fun project(pose: Pose): Offset? {
                val worldCoords = floatArrayOf(pose.tx(), pose.ty(), pose.tz(), 1f)
                val clipCoords = FloatArray(4); val temp = FloatArray(4)
                Matrix.multiplyMV(temp, 0, viewMatrix, 0, worldCoords, 0)
                Matrix.multiplyMV(clipCoords, 0, projMatrix, 0, temp, 0)
                if (clipCoords[3] > 0f) {
                    val ndcX = clipCoords[0] / clipCoords[3]; val ndcY = clipCoords[1] / clipCoords[3]
                    return Offset((ndcX + 1f) * viewWidth / 2f, (1f - ndcY) * viewHeight / 2f)
                }
                return null
            }

            val hit = currentHit
            if (hit != null && hit.trackable is Plane) {
                val hitPose = hit.hitPose
                val radius = 0.05f 
                val pointsCount = 24
                val circleOffsets = mutableListOf<Offset>()
                for (i in 0..pointsCount) {
                    val angle = (i * 2 * Math.PI / pointsCount).toFloat()
                    val localX = cos(angle.toDouble()).toFloat() * radius
                    val localZ = sin(angle.toDouble()).toFloat() * radius
                    val worldPose = hitPose.compose(Pose.makeTranslation(localX, 0f, localZ))
                    project(worldPose)?.let { circleOffsets.add(it) }
                }
                if (circleOffsets.size > 2) {
                    for (i in 0 until circleOffsets.size - 1) {
                        drawLine(
                            color = Color.Green,
                            start = circleOffsets[i],
                            end = circleOffsets[i+1],
                            strokeWidth = 6f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f))
                        )
                    }
                }
            }

            try {
                val pointCloud = frame.acquirePointCloud()
                val pointsArr = pointCloud.points
                for (i in 0 until pointCloud.points.remaining() / 4 step 15) {
                    val p = project(Pose(floatArrayOf(pointsArr.get(i*4), pointsArr.get(i*4+1), pointsArr.get(i*4+2)), floatArrayOf(0f,0f,0f,1f)))
                    if (p != null) drawCircle(Color.Cyan.copy(0.2f), 3f, p)
                }
                pointCloud.release()
            } catch (e: Exception) {}

            val activeAnchors = anchors.filter { it.trackingState == TrackingState.TRACKING }
            if (activeAnchors.size >= 2) {
                for (i in 0 until activeAnchors.size - 1) {
                    val p1 = project(activeAnchors[i].pose)
                    val p2 = project(activeAnchors[i+1].pose)
                    if (p1 != null && p2 != null) {
                        drawLine(Color.White, p1, p2, strokeWidth = 12f)
                        val dist = calculateDistance(activeAnchors[i].pose, activeAnchors[i+1].pose)
                        drawDistanceText(p1, p2, dist)
                    }
                }
            }

            if (activeAnchors.isNotEmpty() && hit != null) {
                val lastProj = project(activeAnchors.last().pose)
                val currentProj = Offset(viewWidth / 2f, viewHeight / 2f)
                if (lastProj != null) {
                    drawLine(
                        color = Color.White.copy(0.7f),
                        start = lastProj,
                        end = currentProj,
                        strokeWidth = 6f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 12f))
                    )
                    val liveDist = calculateDistance(activeAnchors.last().pose, hit.hitPose)
                    drawDistanceText(lastProj, currentProj, liveDist)
                }
            }

            activeAnchors.forEach { anchor ->
                project(anchor.pose)?.let { pos ->
                    drawCircle(Color.White, 16f, pos)
                    drawCircle(Color.White, 22f, pos, style = Stroke(6f))
                }
            }
        }

        val crosshairColor = if (currentHit != null) Color.Green else Color.Red
        Box(Modifier.align(Alignment.Center)) {
            if (currentHit == null || currentHit?.trackable !is Plane) {
                Box(Modifier.size(32.dp).border(2.dp, crosshairColor, CircleShape))
            }
            Box(Modifier.size(4.dp).background(crosshairColor, CircleShape).align(Alignment.Center))
        }

        Surface(Modifier.align(Alignment.TopCenter).padding(top = 64.dp), color = Color.Black.copy(0.5f), shape = RoundedCornerShape(8.dp)) {
            Text(trackingMsg, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp).fillMaxWidth().padding(horizontal = 48.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { anchors = emptyList() }, modifier = Modifier.size(56.dp).background(Color.Black.copy(0.3f), CircleShape)) {
                Icon(Icons.Default.Delete, "Clear", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            LargeFloatingActionButton(
                onClick = {
                    currentHit?.let { anchors = anchors + it.createAnchor() }
                },
                containerColor = if (currentHit != null) Color.White else Color.Gray.copy(0.5f), 
                shape = CircleShape, 
                modifier = Modifier.size(80.dp)
            ) { Icon(Icons.Default.Add, "Add", Modifier.size(40.dp)) }
            Spacer(Modifier.size(56.dp))
        }
    }
}

private fun calculateDistance(p1: Pose, p2: Pose): Float {
    return sqrt((p1.tx()-p2.tx()).pow(2) + (p1.ty()-p2.ty()).pow(2) + (p1.tz()-p2.tz()).pow(2))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDistanceText(p1: Offset, p2: Offset, dist: Float) {
    val mid = Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
    val text = String.format(Locale.US, "%.1f cm", dist * 100)
    val paint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 52f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        setShadowLayer(10f, 0f, 0f, android.graphics.Color.BLACK)
    }
    drawContext.canvas.nativeCanvas.drawText(text, mid.x, mid.y - 20f, paint)
}

class BackgroundRenderer {
    var textureId = -1 ; private set
    private val quadCoords = floatArrayOf(-1.0f, -1.0f, -1.0f, 1.0f, 1.0f, -1.0f, 1.0f, 1.0f)
    private val quadTexCoords = floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0.0f)
    private val transformedTexCoords = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var program = -1

    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, "attribute vec4 a_Pos; attribute vec2 a_Tex; varying vec2 v_Tex; void main() { gl_Position = a_Pos; v_Tex = a_Tex; }")
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v_Tex; uniform samplerExternalOES s_Tex; void main() { gl_FragColor = texture2D(s_Tex, v_Tex); }")
        program = GLES20.glCreateProgram().also { GLES20.glAttachShader(it, vShader); GLES20.glAttachShader(it, fShader); GLES20.glLinkProgram(it) }
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) frame.transformDisplayUvCoords(createBuffer(quadTexCoords), transformedTexCoords)
        GLES20.glUseProgram(program)
        GLES20.glDepthMask(false)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        val posHandle = GLES20.glGetAttribLocation(program, "a_Pos")
        val texHandle = GLES20.glGetAttribLocation(program, "a_Tex")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, createBuffer(quadCoords))
        GLES20.glEnableVertexAttribArray(texHandle)
        transformedTexCoords.position(0)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, transformedTexCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDepthMask(true)
    }

    private fun loadShader(type: Int, code: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, code); GLES20.glCompileShader(it) }
    private fun createBuffer(array: FloatArray) = ByteBuffer.allocateDirect(array.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(array).apply { position(0) }
}

@Composable
fun InstallButton(context: Context, onInstallingChange: (Boolean) -> Unit) {
    Button(onClick = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return@Button
        }
        onInstallingChange(true)
        installARCoreFromAssets(context) { onInstallingChange(false) }
    }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("安装离线 ARCore 框架") }
}

fun isPackageInstalled(context: Context, pkg: String) = try { context.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

fun installARCoreFromAssets(context: Context, onComplete: () -> Unit) {
    val file = File(context.cacheDir, "arcore.apk")
    try {
        context.assets.open("arcore.apk").use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) { Toast.makeText(context, "失败: ${e.message}", Toast.LENGTH_LONG).show() } finally { onComplete() }
}
