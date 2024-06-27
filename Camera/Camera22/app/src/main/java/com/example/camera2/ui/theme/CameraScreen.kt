package com.example.camera2.ui.theme

import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import com.example.camera2.R
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay


enum class FlashMode{
    OFF,
    ON,
    AUTO,
    TORCH
}

@Composable
fun CameraScreen(cameraHelper: CameraHelper) {
    val context = LocalContext.current;
    val textureView = remember {
        TextureView(context)
    }
    var isObjectDetection by remember {
            mutableStateOf(false)
    }
    var flashMode by remember {
        mutableStateOf(FlashMode.OFF)
    }
    var dropDownExpanded by remember {
        mutableStateOf(false)
    }
    var tapPosition by remember {
        mutableStateOf<Pair<Float,Float>?>(null)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(cameraHelper, textureView, Modifier.fillMaxSize(), tapPosition)
        Row(modifier = Modifier
            .align(Alignment.BottomCenter)
            .background(Color.White)
            .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly)
            {
            IconButton(
                onClick = {
                cameraHelper.switchCamera(textureView);
            },
                ) {
                Icon(painter = painterResource(R.drawable.flip_camera),
                    contentDescription = "Camera switch",
                    tint = Color.LightGray)
            }
            IconButton(
                onClick = {
                    cameraHelper.takePhoto()
                },
                enabled = !isObjectDetection
            ) {
                Icon(painter = if(!isObjectDetection) painterResource(R.drawable.camera_shutter)
                    else painterResource(R.drawable.disabled),
                    contentDescription = "Click Photo",
                    tint = Color.LightGray)
            }
            IconButton(
                onClick = {
                    isObjectDetection = !isObjectDetection;
                    if(!isObjectDetection){
                        cameraHelper.isObjectDetection = false;
                        cameraHelper.stopProcessingFrames();
                        cameraHelper.closeCamera();
                        cameraHelper.openCamera(textureView);
                    }
                    else{
                        cameraHelper.startDetection(textureView);
                    }
                }
            ) {
                Icon(painter = if (!isObjectDetection) painterResource(R.drawable.object_detection)
                    else painterResource(R.drawable.back_arrow),
                    contentDescription = "Detection",
                    tint = Color.LightGray)
            }

            }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopEnd)
                .padding(4.dp)
        ) {
         IconButton(onClick = {
             dropDownExpanded = true
         }) {
             val flashIcon = when (flashMode){
                 FlashMode.OFF -> R.drawable.flash_off
                 FlashMode.ON -> R.drawable.flash_on
                 FlashMode.AUTO -> R.drawable.flash_auto
                 FlashMode.TORCH -> R.drawable.flash_torch
             }
             Icon(
                 painter = painterResource(flashIcon),
                 contentDescription = "Camera flash options",
                 tint = Color.LightGray
             )
         }
            DropdownMenu(expanded = dropDownExpanded,
                onDismissRequest = { dropDownExpanded= false }) {
             DropdownMenuItem(text ={Text(text = "Flash off") } ,
                 onClick = { flashMode = FlashMode.OFF
                     cameraHelper.setFlashMode(flashMode)
                     dropDownExpanded = false
                 })
            DropdownMenuItem(text ={Text(text = "Flash on") } ,
                onClick = { flashMode = FlashMode.ON
                    cameraHelper.setFlashMode(flashMode)
                    dropDownExpanded = false
                })
            DropdownMenuItem(text ={Text(text = "Flash auto") } ,
                onClick = { flashMode = FlashMode.AUTO
                    cameraHelper.setFlashMode(flashMode)
                    dropDownExpanded = false
                })
            DropdownMenuItem(text ={Text(text = "Flash torch") } ,
                onClick = { flashMode = FlashMode.TORCH
                    cameraHelper.setFlashMode(flashMode)
                    dropDownExpanded = false
                })
            }


        }

        }
    }




@Composable
fun CameraPreview(cameraHelper: CameraHelper, textureView: TextureView, modifier: Modifier,
                  tapPosition: Pair<Float,Float>?){
    var markerPosition by remember {
        mutableStateOf<Pair<Float,Float>?>(null)
    }
    AndroidView(
        factory ={
            textureView.apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener{
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        cameraHelper.openCamera(this@apply);
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        cameraHelper.closeCamera();
                        return true;
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        if(cameraHelper.isObjectDetection){
                            cameraHelper.processFrames(this@apply)
                        }
                    }
                }
                setOnTouchListener { v, event ->
                    if(event.action == MotionEvent.ACTION_DOWN){
                        val position = Pair(event.x,event.y)
                        markerPosition = position
                        cameraHelper.setFocusPoint(event.x,event.y,this)
                        v.performClick();
                    }
                    true
                }
            }
        } )
    markerPosition?.let{
        showTapMarker(it)
        LaunchedEffect(markerPosition){
            delay(300L)
            markerPosition =null
        }
    }
}

@Composable
fun showTapMarker(position: Pair<Float,Float>){
    Image(
        painter = painterResource(R.drawable.focus),
        contentDescription = null,
        modifier = Modifier
            .size(80.dp)
            .offset {
                IntOffset(position.first.toInt() - 50, position.second.toInt() - 50)
            }
    )
}

