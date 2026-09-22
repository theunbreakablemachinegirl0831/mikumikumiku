package mmm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import mmm.app.ui.theme.MmmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MmmApplication
        setContent {
            MmmTheme {
                MmmApp(app)
            }
        }
    }
}
