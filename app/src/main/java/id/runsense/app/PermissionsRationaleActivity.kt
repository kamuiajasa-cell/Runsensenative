package id.runsense.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView

/** Layar kebijakan singkat yang dibuka Health Connect dari halaman izin. */
class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            setPadding(pad, pad, pad, pad)
            setTextColor(Color.parseColor("#FAFAFA"))
            textSize = 16f
            text = "Penggunaan data kesehatan di RunSense\n\n" +
                "RunSense hanya MENULIS ke Health Connect, tidak membaca data yang sudah ada.\n\n" +
                "Yang ditulis: sesi lari/jalan (waktu, jarak, elevasi), langkah harian, detak jantung " +
                "istirahat, HRV, dan SpO2. Semuanya berasal dari akun Intervals.icu milikmu.\n\n" +
                "Data tidak dikirim ke server RunSense dan tidak dibagikan ke pihak ketiga. " +
                "Kamu bisa mencabut izin kapan saja lewat pengaturan Health Connect."
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            addView(text)
        })
    }
}
