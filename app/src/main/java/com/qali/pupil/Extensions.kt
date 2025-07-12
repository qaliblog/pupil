// Extensions.kt
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

private const val TAG = "Extensions"

fun ImageProxy.toBitmap(): Bitmap? {
    val plane = this.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.capacity())
    buffer.get(bytes)
    val bitmap = Bitmap.createBitmap(this.width, this.height, Bitmap.Config.ARGB_8888)
    try {
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
    }catch(e: RuntimeException){
        Log.e(TAG, "Error in converting ImageProxy to Bitmap", e)
        return null
    }
    return bitmap
}