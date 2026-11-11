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

    override fun onStart() {
        super.onStart()
        // The service that puts the EQ on other apps may only be started while on screen.
        (application as MmmApplication).syncSystemEq()
    }
}
