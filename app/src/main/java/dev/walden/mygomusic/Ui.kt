package dev.walden.mygomusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

object Ui {
    const val BG = 0xFF121212.toInt()
    const val SURFACE = 0xFF1E1E1E.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val SUBTEXT = 0xFFAAAAAA.toInt()
    const val ACCENT = 0xFFE60026.toInt()

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun text(context: Context, s: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    fun cover(context: Context, url: String?, sizeDp: Int): ImageView =
        ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(SURFACE)
            if (!url.isNullOrBlank()) load(url) { crossfade(true) }
        }

    fun formatTime(ms: Long): String {
        val total = (ms / 1000).toInt()
        return "%d:%02d".format(total / 60, total % 60)
    }

    fun trackRow(context: Context, track: Track, onTap: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onTap() }
        }
        row.addView(cover(context, track.album.imageUrl, 48))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(context, 12), 0, dp(context, 8), 0)
        }
        texts.addView(text(context, track.name, 15f, if (track.unavailable) SUBTEXT else TEXT).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        texts.addView(text(
            context,
            listOf(track.artistNames(), track.album.name).filter { it.isNotBlank() }.joinToString(" · "),
            12f, SUBTEXT,
        ).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        row.addView(texts)
        if (track.duration > 0) row.addView(text(context, formatTime(track.duration), 12f, SUBTEXT))
        return row
    }

    fun qrBitmap(content: String, size: Int): Bitmap? = try {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
        bmp
    } catch (e: Exception) {
        null
    }
}

class TrackAdapter(
    private val tracks: List<Track>,
    private val onTap: (Int) -> Unit,
) : RecyclerView.Adapter<TrackAdapter.Holder>() {

    class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val cover: ImageView = row.getChildAt(0) as ImageView
        val title: TextView = (row.getChildAt(1) as LinearLayout).getChildAt(0) as TextView
        val sub: TextView = (row.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
        val duration: TextView = row.getChildAt(2) as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 8))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(Ui.cover(context, null, 48))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 8), 0)
        }
        texts.addView(Ui.text(context, "", 15f))
        texts.addView(Ui.text(context, "", 12f, Ui.SUBTEXT))
        row.addView(texts)
        row.addView(Ui.text(context, "", 12f, Ui.SUBTEXT))
        return Holder(row)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = tracks[position]
        val context = holder.row.context
        holder.cover.setImageDrawable(null)
        if (!track.album.imageUrl.isNullOrBlank()) holder.cover.load(track.album.imageUrl) { crossfade(true) }
        holder.title.text = track.name
        holder.title.setTextColor(if (track.unavailable) Ui.SUBTEXT else Ui.TEXT)
        holder.sub.text = listOf(track.artistNames(), track.album.name)
            .filter { it.isNotBlank() }.joinToString(" · ")
        holder.duration.text = if (track.duration > 0) Ui.formatTime(track.duration) else ""
        holder.row.setOnClickListener { onTap(position) }
    }

    override fun getItemCount(): Int = tracks.size
}

class SimpleRowAdapter<T>(
    private val items: List<T>,
    private val titleOf: (T) -> String,
    private val subOf: (T) -> String,
    private val imageOf: (T) -> String?,
    private val onTap: (T) -> Unit,
) : RecyclerView.Adapter<SimpleRowAdapter.Holder>() {

    class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val cover: ImageView = row.getChildAt(0) as ImageView
        val title: TextView = (row.getChildAt(1) as LinearLayout).getChildAt(0) as TextView
        val sub: TextView = (row.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 8))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(Ui.cover(context, null, 48))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(Ui.dp(context, 12), 0, 0, 0)
        }
        texts.addView(Ui.text(context, "", 15f).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        texts.addView(Ui.text(context, "", 12f, Ui.SUBTEXT).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(texts)
        return Holder(row)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.cover.setImageDrawable(null)
        val url = imageOf(item)
        if (!url.isNullOrBlank()) holder.cover.load(url) { crossfade(true) }
        holder.title.text = titleOf(item)
        holder.sub.text = subOf(item)
        holder.row.setOnClickListener { onTap(item) }
    }

    override fun getItemCount(): Int = items.size
}

class CardAdapter<T>(
    private val items: List<T>,
    private val titleOf: (T) -> String,
    private val imageOf: (T) -> String?,
    private val subOf: (T) -> String,
    private val onTap: (T) -> Unit,
) : RecyclerView.Adapter<CardAdapter.Holder>() {

    class Holder(val box: LinearLayout) : RecyclerView.ViewHolder(box) {
        val cover: ImageView = box.getChildAt(0) as ImageView
        val title: TextView = box.getChildAt(1) as TextView
        val sub: TextView = box.getChildAt(2) as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(Ui.dp(context, 120), ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4))
        }
        box.addView(Ui.cover(context, null, 112))
        box.addView(Ui.text(context, "", 13f).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        box.addView(Ui.text(context, "", 11f, Ui.SUBTEXT).apply { maxLines = 1 })
        return Holder(box)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.cover.setImageDrawable(null)
        val url = imageOf(item)
        if (!url.isNullOrBlank()) holder.cover.load(url) { crossfade(true) }
        holder.title.text = titleOf(item)
        holder.sub.text = subOf(item)
        holder.box.setOnClickListener { onTap(item) }
    }

    override fun getItemCount(): Int = items.size
}
