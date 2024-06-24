package com.example.camera2.ui.theme

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
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
//import org.tensorflow.lite.task.vision.detector.Detection
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
    //private lateinit var objectDetectorHelper: ObjectDetectorHelper;
    private lateinit var imageProcessor: ImageProcessor;
    private lateinit var model: SsdMobilenetv11
    private val paint = Paint();
    private lateinit var labels:List<String>
    private var detectedObject: String = "";
    private var isFlashOn: Boolean = false;
    private var flashMode: FlashMode = FlashMode.OFF;

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
            //cameraId = cameraManager.cameraIdList[0];
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


//    private fun startPreview(){
//        labels = FileUtil.loadLabels(context, "labels.txt")
//        val surfaceTexture: SurfaceTexture = textureView.surfaceTexture!!
//        surfaceTexture.setDefaultBufferSize(textureView.width,textureView.height);
//        val surface = Surface(surfaceTexture);
//        imageReader = ImageReader.newInstance(textureView.width,textureView.height,ImageFormat.JPEG,1);
//        imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);
//        val imageReaderSurface = imageReader.surface;
//        captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
//        captureRequestBuilder.addTarget(surface);
//        cameraDevice.createCaptureSession(listOf(surface, imageReaderSurface), object : CameraCaptureSession.StateCallback(){
//            override fun onConfigured(session: CameraCaptureSession) {
//                cameraCaptureSession = session;
//                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
//                try{
//                    cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(),null, backgroundHandler);
//                }
//                catch (e: CameraAccessException){
//                    e.printStackTrace();
//                }
//            }
//            override fun onConfigureFailed(session: CameraCaptureSession) {
//                TODO("Not yet implemented")
//            }
//        },backgroundHandler)
//    }
//

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
        applyFlashMode();
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
            if(score > 0.70){
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
            path,uri ->
        }


    }


    fun takePhoto(){
        try{
            Log.d("TakePhoto", "Photo captured");
            val captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader.surface);
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            applyFlashMode();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION,getJpegOrientation());
            //setFlashMode(flashMode);

            cameraCaptureSession.stopRepeating();
            cameraCaptureSession.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback(){
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result);
                    startPreview();
                }
                        }, backgroundHandler);
        }catch (e: CameraAccessException){
            e.printStackTrace();
        }
    }

    //fix pic orientation as it is getting rotated by 90 so doing + 270
    private fun getJpegOrientation(): Int{
        val sensorOrientation = cameraManager.getCameraCharacteristics(cameraDevice.id)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0;
        val display = context.display;
        val rotation = display?.rotation ?: 0;
        return (sensorOrientation + ORIENTATIONS.get(rotation) + 270) % 360;
    }


    fun switchCamera(textureView: TextureView){
        val TAG = "Switch Camera"
        Log.d(TAG, "switch called");
        isFrontCamera = !isFrontCamera;
        closeCamera();
        openCamera(textureView);
    }


    fun closeCamera(){
        try{
            cameraCaptureSession.close();
            cameraDevice.close();
            backgroundHandlerThread!!.quitSafely();
            backgroundHandlerThread?.join();
            backgroundHandlerThread = null;
            backgroundHandler = null;

        }catch (e: Exception){
            e.printStackTrace();
        }
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


//    fun setFlashMode(flashMode: FlashMode){
//        if(::captureRequestBuilder.isInitialized && ::cameraCaptureSession.isInitialized) {
//            this.flashMode = flashMode;
//            applyFlashMode(flashMode);
//            //cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(),null,backgroundHandler);
//        }
//    }


    fun setFlashMode(flashMode: FlashMode){
        this.flashMode = flashMode;
        if(::cameraCaptureSession.isInitialized && ::captureRequestBuilder.isInitialized){
            applyFlashMode();
            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null,backgroundHandler);
        }
    }

    private fun applyFlashMode(){
        try{
            if(::captureRequestBuilder.isInitialized ) {

                when(flashMode){
                FlashMode.OFF -> {
                    captureRequestBuilder.set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_OFF
                    )
                    captureRequestBuilder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF)
                    //when switched from torch to off, torch is off
                }
                FlashMode.ON -> {
                    captureRequestBuilder.set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_OFF
                    ); //make it work like flash off + pic
                    captureRequestBuilder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_SINGLE)

                }
                FlashMode.AUTO -> {
                    captureRequestBuilder.set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                    );
                    captureRequestBuilder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_SINGLE)

                }FlashMode.TORCH -> {
                    captureRequestBuilder.set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON
                    );
                    captureRequestBuilder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_TORCH)

                }}
}
        }catch (e: CameraAccessException){
            e.printStackTrace();
    }
}

    fun setFocusPoint(x:Float, y: Float, textureView: TextureView){
        if(!::cameraDevice.isInitialized) return;
        val sensorSize = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Log.d("A", sensorSize.toString());
        val focusArea = calculateFocusArea(x,y,textureView.width,textureView.height,sensorSize);
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusArea));
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START);
        cameraCaptureSession.capture(captureRequestBuilder.build(),null,null);


}
    fun calculateFocusArea(x:Float, y: Float, w: Int, h: Int, s: Rect?): MeteringRectangle{
        val focusAreaSize = 200;
        val centerX = (x/w * s!!.width()).toInt();
        val centerY = (y/h * s.height()).toInt();
        val left = Math.max(centerX - focusAreaSize/ 2,0);
        val top = Math.max(centerY - focusAreaSize/2 , 0);
        val right = Math.min(left +focusAreaSize, s.width());
        val bottom = Math.min(top+ focusAreaSize, s.height());
        return MeteringRectangle(left,top,right - left, bottom - top, MeteringRectangle.METERING_WEIGHT_MAX)
    }


}