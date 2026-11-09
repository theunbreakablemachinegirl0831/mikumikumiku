package mmm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import mmm.app.ui.theme.MmmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The backdrop runs under the status and navigation bars, as in the reference.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as MmmApplication
        setContent {
            MmmTheme {
                MmmApp(app)
            }
        }
    }
}
