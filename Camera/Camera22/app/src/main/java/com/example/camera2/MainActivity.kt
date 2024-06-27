package com.example.camera2

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.camera2.ui.theme.Camera2Theme
import com.example.camera2.ui.theme.CameraHelper
import com.example.camera2.ui.theme.CameraScreen

class MainActivity : ComponentActivity() {

private lateinit var cameraHelper: CameraHelper;
private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()){
    isGranted ->
    if(isGranted){
        setContent {
            Camera2Theme {
                CameraScreen(cameraHelper)
            }
        }
    }
    else{
        showPermissionDenied();
    }
}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraHelper = CameraHelper(this);
        if(!hasPermissionRequired()){
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
        else{
        setContent {
            Camera2Theme {
                CameraScreen(cameraHelper)
            }
        }
        }
    }

    override fun onDestroy() {
        super.onDestroy();
        cameraHelper.stopProcessingFrames();
        cameraHelper.closeCamera();
    }


private fun hasPermissionRequired(): Boolean{
    val permissions = arrayOf(
        Manifest.permission.CAMERA
    )
    return permissions.all {
        ContextCompat.checkSelfPermission(
            applicationContext,
            it
        ) == PackageManager.PERMISSION_GRANTED
    }
}

private fun showPermissionDenied(){
    setContent {
        Camera2Theme {
            Surface(modifier = Modifier
                .fillMaxSize()
                .background(color = MaterialTheme.colorScheme.background)) {
                Box(modifier = Modifier
                    .fillMaxSize()
                    .background(color = Color.DarkGray),
                    contentAlignment = Alignment.Center){
                    Column (modifier = Modifier.padding(20.dp)){
                        Text(
                            text = "Camera permissions are needed\nPlease enable permissions in settings to use this app",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(onClick = {
                            goToSettings()
                        }) {
                            Text(text = "Go to settings")
                        }
                    }
                }
            }
        }
    }
}

private fun goToSettings(){
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply{
            data = Uri.fromParts("package", packageName,null);
        }
    startActivity(intent);
    }


}
