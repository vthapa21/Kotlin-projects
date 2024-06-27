package com.example.camera2.ui.theme

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.HandlerThread
import android.view.TextureView
import android.os.Handler
import android.util.Log
import android.util.SparseArray
import android.view.Surface
import android.widget.Toast
import com.example.camera2.ml.SsdMobilenetv11
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.File
import java.io.FileOutputStream
import java.lang.RuntimeException
import java.nio.ByteBuffer

class CameraHelper (private val context: Context) {
    private lateinit var cameraDevice: CameraDevice;
    private lateinit var captureRequestBuilder: CaptureRequest.Builder;
    private lateinit var cameraCaptureSession: CameraCaptureSession;
    private var cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager;
    private var backgroundHandler: Handler? = null;
    private var backgroundHandlerThread: HandlerThread? = null;
    private lateinit var imageReader: ImageReader;
    private lateinit var textureView: TextureView;
    private lateinit var cameraId: String;
    private var isFrontCamera: Boolean = false;
    var isObjectDetection: Boolean = false;
    private lateinit var imageProcessor: ImageProcessor;
    private lateinit var model: SsdMobilenetv11
    private lateinit var labels:List<String>
    private var detectedObject: String = "";
    private var flashMode: FlashMode = FlashMode.OFF;
    private var TAG = "CAMERA2-SAMPLE";

    companion object{
        private val ORIENTATIONS = SparseArray<Int>();
        init {
            ORIENTATIONS.append(Surface.ROTATION_0,90);
            ORIENTATIONS.append(Surface.ROTATION_90,0);
            ORIENTATIONS.append(Surface.ROTATION_180,270);
            ORIENTATIONS.append(Surface.ROTATION_270,180);
        }
    }


    @SuppressLint("MissingPermission")  //already permissions check is done,refactor here if needed
    fun openCamera(textureView: TextureView){
        backgroundHandlerThread = HandlerThread("Camera").also { it.start() }
        backgroundHandler = Handler(backgroundHandlerThread!!.looper);
        this.textureView = textureView;
        try{
            cameraId = getCameraId(); //back will be first
            cameraManager.openCamera(cameraId,
                object : CameraDevice.StateCallback(){
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera;
                        startPreview();
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        cameraDevice.close();
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        cameraDevice.close();
                    }
                },
                backgroundHandler
                )
        }
        catch (e: CameraAccessException){
            e.printStackTrace();
        }
    }

    private fun getCameraId(): String{
        for(id in cameraManager.cameraIdList){
            val characteristics = cameraManager.getCameraCharacteristics(id);
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if ((!isFrontCamera && facing == CameraCharacteristics.LENS_FACING_BACK) ||
                isFrontCamera && facing == CameraCharacteristics.LENS_FACING_FRONT){
                return id
            }
        }
        throw RuntimeException("No camera found")
    }

    private fun startPreview(){
        labels = FileUtil.loadLabels(context, "labels.txt")
        val surfaceTexture: SurfaceTexture = textureView.surfaceTexture!!
        surfaceTexture.setDefaultBufferSize(textureView.width,textureView.height);
        val surface = Surface(surfaceTexture);
        imageReader = ImageReader.newInstance(textureView.width,textureView.height,ImageFormat.JPEG,1);
        imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);
        val imageReaderSurface = imageReader.surface;
        captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        captureRequestBuilder.addTarget(surface);
        if(flashMode == FlashMode.TORCH){
            //calling this for torch will ensure torch will be active for next pic
            applyFlashMode();}
        cameraDevice.createCaptureSession(listOf(surface, imageReaderSurface), object : CameraCaptureSession.StateCallback(){
            override fun onConfigured(session: CameraCaptureSession) {
                cameraCaptureSession = session;
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                try{
                    cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(),null, backgroundHandler);
                }
                catch (e: CameraAccessException){
                    e.printStackTrace();
                }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                TODO("Not yet implemented")
            }
        },backgroundHandler)
    }


    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image: Image = reader.acquireLatestImage();
        val buffer: ByteBuffer = image.planes[0].buffer; //raw image data
        val bytes = ByteArray(buffer.remaining());
        buffer.get(bytes);
        //writes into DCIM folder
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "image_${System.currentTimeMillis()}.jpg");
        FileOutputStream(file).use {
            it.write(bytes);
        }
        image.close();
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath),null){
            _,_ ->
        }
    }

    fun takePhoto(){
        try{
            Log.d(TAG, "Photo captured");
            val captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader.surface);
            //applyFlashModeInitial(captureBuilder); passing with curr builder doesn't seem to trigger flash (AE/flash/templates/requestBuilder????)
            //applyFlashMode();
            if (flashMode == FlashMode.AUTO || flashMode == FlashMode.ON){
                //creating a fake pre flash illusion by setting torch on is the workaround to capture flash in pics
                captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                Log.d(TAG, "Enabling torch")
            }
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION,getJpegOrientation());
            cameraCaptureSession.stopRepeating();
            cameraCaptureSession.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback(){
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result);
                    startPreview();
                }
            },
                backgroundHandler);
        }
        catch (e: CameraAccessException){
            e.printStackTrace();
        }
    }


    //fix pic orientation as it is getting rotated by 90 so doing + 270
    private fun getJpegOrientation(): Int {
        val sensorOrientation = cameraManager.getCameraCharacteristics(cameraDevice.id)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0;
        val display = context.display;
        val rotation = display?.rotation ?: 0;
        return (sensorOrientation + ORIENTATIONS.get(rotation) + 270) % 360;
    }

    fun switchCamera(textureView: TextureView){
        Log.d(TAG, "switch called");
        isFrontCamera = !isFrontCamera;
        closeCamera();
        openCamera(textureView);
    }

     @SuppressLint("MissingPermission")
     fun startDetection(textureView: TextureView){
         closeCamera();
         isObjectDetection = true;
         backgroundHandlerThread = HandlerThread("Camera").also { it.start() }
         backgroundHandler = Handler(backgroundHandlerThread!!.looper);
         this.textureView = textureView;
         try{
             //cameraId = cameraManager.cameraIdList[0];
             cameraId = getCameraId(); //back will be first
             cameraManager.openCamera(cameraId,
                 object : CameraDevice.StateCallback(){
                     override fun onOpened(camera: CameraDevice) {
                         cameraDevice = camera;
                         startPreviewDetection();
                     }
                     override fun onDisconnected(camera: CameraDevice) {
                         cameraDevice.close();
                     }

                     override fun onError(camera: CameraDevice, error: Int) {
                         cameraDevice.close();
                     }
                 },
                 backgroundHandler
             )
         }
         catch (e: CameraAccessException){
             e.printStackTrace();
         }
    }


    fun setFlashMode(flashMode: FlashMode){
        this.flashMode = flashMode;
        if (::cameraCaptureSession.isInitialized && ::captureRequestBuilder.isInitialized ){
            applyFlashMode();
            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null,backgroundHandler);
        }
//        if(::cameraCaptureSession.isInitialized && ::captureRequestBuilder.isInitialized){
//            applyFlashMode(); //will start flash trial here once when selected
//            //startPreview();
//            //cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null,backgroundHandler);
//        }
    }

//    private fun applyFlashModeInitial(captureBuilder: CaptureRequest.Builder){
//        try{
//            Log.d(TAG, flashMode.toString())
//            when(flashMode){
//            FlashMode.OFF -> {
//                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
//            }
//            FlashMode.ON -> {
//                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH); //make it work like flash off + pic + flash
//                captureBuilder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF)
//            }
//            FlashMode.AUTO -> {
//                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
//                captureBuilder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF)
//            }FlashMode.TORCH -> {
//                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
//                captureBuilder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_TORCH)
//            }}
//        }catch (e: CameraAccessException){
//            e.printStackTrace();
//        }
//    }

    private fun applyFlashMode(){
        try{
            if(::captureRequestBuilder.isInitialized ) {
                Log.d(TAG, flashMode.toString())
                when(flashMode){
                    FlashMode.OFF -> {
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        captureRequestBuilder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF)
                        //when switched from torch to off, torch will be off
                    }
                    FlashMode.ON -> {
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH); //make it work like flash off + pic + flash
                        captureRequestBuilder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF)
                    }
                    FlashMode.AUTO -> {
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                        captureRequestBuilder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF)
                    }
                    FlashMode.TORCH -> {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                    captureRequestBuilder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_TORCH)
                }}
            }
        }
        catch (e: CameraAccessException){
            e.printStackTrace();
        }
    }


    fun setFocusPoint(x:Float, y: Float, textureView: TextureView){
        if(!::cameraDevice.isInitialized) return;
        val sensorOrientation = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION);
        val deviceRotation = context.display?.rotation ?: 0;
        val rotation = ORIENTATIONS[deviceRotation];
        val sensorSize: Rect = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return; //active area of camera sensor in pixels
        val rotationCompensation = if ( isFrontCamera){
            (sensorOrientation?.plus(rotation))?.rem(360)
        } else{
            ((sensorOrientation?.minus(rotation)?.plus(360))?.rem(360))
        }
//        var outputX: Float;
//        var outputY: Float;
//        val textureWidth = textureView.width.toFloat();
//        val textureViewHeight = textureView.height.toFloat();
//        if(rotationCompensation == 90 || rotationCompensation == 270){
//            outputX = y;
//            outputY = x;
//        }
//        else{
//            outputX = x;
//            outputY = y;
//        }
//        when (rotationCompensation){
//            90 -> outputY = textureViewHeight - outputY;
//            180 -> {
//                outputX = textureWidth - outputX;
//                outputY = textureViewHeight - outputY;
//            }
//            270 -> outputX = textureWidth - outputX
//        }
//        if(isFrontCamera) {
//            outputX = textureWidth - outputX;
//        }
//        outputX /= textureWidth;
//        outputY /= textureViewHeight;
//        val focusAreaTouch = MeteringRectangle(
//            (outputX * sensorSize.width()).toInt(),
//            (outputY * sensorSize.height()).toInt(),
//            (sensorSize.width() * 10).toInt(),
//            (sensorSize.height() * 10).toInt(),
//            MeteringRectangle.METERING_WEIGHT_MAX -1
//        ) // regions to focus touch on
          val maxRegionsAf = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)
          if (maxRegionsAf == null || maxRegionsAf < 1) return;
//        cameraCaptureSession.stopRepeating();
//        val cancelBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
//        val surface = Surface(textureView.surfaceTexture);
        //cancelBuilder.addTarget(surface);
//        cancelBuilder.addTarget(imageReader.surface)
//        cancelBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
 //       cameraCaptureSession.capture(cancelBuilder.build(),null,backgroundHandler);
//        //val focusBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
//        captureRequestBuilder.addTarget(surface);
//        captureRequestBuilder.addTarget(imageReader.surface);
//        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusAreaTouch));
//        val maxRegionsAe = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
//        if(maxRegionsAe != null && maxRegionsAe > 0){
//            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusAreaTouch));
//        }
//        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
//        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        //val focusArea = calculateFocusArea(x,y); //for screen space only
        //need to cancel my ongoing request
        //cameraCaptureSession.stopRepeating();
        //captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);

        val focusAreaSensor = calculateFocusAreaSensor(x,y,textureView.width, textureView.height,sensorSize); //map screen coords to sensor space coords
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusAreaSensor));
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START);
        cameraCaptureSession.capture(captureRequestBuilder.build(),null,backgroundHandler);
        //Log.d(TAG, "Setting focus point: $focusArea")
        //Log.d(TAG, "Setting focus point: $focusAreaSensor")
    }

    private fun calculateFocusArea(x:Float, y: Float): MeteringRectangle{
        val focusAreaSize = 150;
        val left = ((x-focusAreaSize/2).toInt()).coerceIn(0,1000);
        val top = ((y-focusAreaSize/2).toInt()).coerceIn(0,1000);
        val right = ((x+focusAreaSize/2).toInt()).coerceIn(0,1000);
        val bottom = ((y+focusAreaSize/2).toInt()).coerceIn(0,1000);
        return MeteringRectangle(Rect(left,top,right,bottom), MeteringRectangle.METERING_WEIGHT_MAX-1)
    }

    //for now this isn't mapped wrt sensor orientation,
    private fun calculateFocusAreaSensor(x: Float, y: Float, width: Int, height: Int, sensor: Rect): MeteringRectangle{
        val focusAreaSize = 150;
        val sensorWidth = sensor.width();
        val sensorHeight = sensor.height();
//        val sensorX = (x / width)  * sensorWidth;
//        val sensorY = (y/ height) * sensorHeight;
//        val left = ((sensorX - focusAreaSize/2).toInt()).coerceIn(0,sensorWidth);
//        val top = ((sensorY - focusAreaSize/2).toInt()).coerceIn(0,sensorHeight);
//        val right = ((sensorX + focusAreaSize/2).toInt()).coerceIn(0,sensorWidth);
//        val bottom = ((sensorY + focusAreaSize/2).toInt()).coerceIn(0,sensorHeight);

        //flipping x & y
        val sensorX = (y/height) * sensorWidth;
        val sensorY= (x/width) * sensorHeight;
        val left = ((sensorX - focusAreaSize).toInt()).coerceIn(0, sensorWidth);
        val top = ((sensorY - focusAreaSize).toInt().coerceIn(0, sensorHeight));
        val right = ((sensorX + focusAreaSize).toInt()).coerceIn(0, sensorWidth);
        val bottom = ((sensorY + focusAreaSize).toInt()).coerceIn(0,sensorHeight);
        return MeteringRectangle(Rect(left,top,right,bottom), MeteringRectangle.METERING_WEIGHT_MAX-1);
    }


    private fun startPreviewDetection(){
        val surfaceTexture: SurfaceTexture = textureView.surfaceTexture!!
        surfaceTexture.setDefaultBufferSize(textureView.width,textureView.height);
        val surface = Surface(surfaceTexture);
        captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        captureRequestBuilder.addTarget(surface);
        cameraDevice.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback(){
            override fun onConfigured(session: CameraCaptureSession) {
                cameraCaptureSession = session;
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                try{
                    cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(),null, backgroundHandler);
                }
                catch (e: CameraAccessException){
                    e.printStackTrace();
                }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                TODO("Not yet implemented")
            }
        },backgroundHandler)
    }


    fun processFrames(textureView: TextureView){
        model = SsdMobilenetv11.newInstance(context); //need labels for efficientdet0 TFlite model
        val bitmap = textureView.bitmap ?: return
        var image = TensorImage.fromBitmap(bitmap);
        imageProcessor = ImageProcessor.Builder().add(ResizeOp(320,320,ResizeOp.ResizeMethod.BILINEAR)).build();
        image = imageProcessor.process(image);
        val outputs = model.process(image);
        val locations = outputs.locationsAsTensorBuffer.floatArray;
        val classes = outputs.classesAsTensorBuffer.floatArray;
        val scores = outputs.scoresAsTensorBuffer.floatArray;
        val numberOfDetections = outputs.numberOfDetectionsAsTensorBuffer.floatArray;
        scores.forEachIndexed { index, score ->
            if(score > 0.75){
                var identifiedLabel: String = labels[classes[index].toInt()]
                if (detectedObject!= identifiedLabel) {
                    detectedObject = identifiedLabel;
                    var scorePercentage: Int= (score*100).toInt();
                    showToast("Object: $detectedObject, Confidence: $scorePercentage%");
                }
            }
        }
        //var textureCanvas = textureView.lockCanvas();
        //if(textureCanvas!=null){
        //textureCanvas.drawBitmap(mutable,0f, 0f, null);
        //textureView.unlockCanvasAndPost(textureCanvas);}
    }



    private fun showToast(message:String){
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    fun stopProcessingFrames(){
        try{
            model.close();
        }
        catch (e: Exception){
            e.printStackTrace();
        }
    }

    fun closeCamera(){
        try{
            cameraCaptureSession.close();
            cameraDevice.close();
            backgroundHandlerThread!!.quitSafely();
            backgroundHandlerThread?.join();
            backgroundHandlerThread = null;
            backgroundHandler = null;
        }
        catch (e: Exception){
            e.printStackTrace();
        }
    }

}