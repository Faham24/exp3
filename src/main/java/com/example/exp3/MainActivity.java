package com.example.exp3;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.location.Location;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Html;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "VoiceAssistant";
    private static final int REQUEST_PERMISSIONS_CODE = 100;

    private static final int CAPTURE_MODE_OCR = 1;
    private static final int CAPTURE_MODE_SCENE_ANALYSIS = 2;
    private int currentCaptureMode;
    // ... (other constants like CAPTURE_MODE_OCR, etc.)
    private static final int CAPTURE_MODE_OBJECT_DETAIL = 4; // New mode

    // ... (other fields like speechRecognizer, httpClient, etc.)
    private ObjectAnalyzer objectAnalyzer; // New analyzer instance
    private SpeechRecognizer speechRecognizer;
    private FusedLocationProviderClient fusedLocationClient;
    private OkHttpClient httpClient;
    private MediaPlayer mediaPlayer;
    private Handler pollingHandler;

    private Button mainButton;
    private boolean isListening = false;

    private double currentLatitude;
    private double currentLongitude;
    private String fetchedAddress;
    private String busStopName;
    private double busStopDistance = -1;
    private String railwayStationName;
    private double railwayStationDistance = -1;
    private int pendingLocationRequests;
    private static final int CAPTURE_MODE_CURRENCY = 3;
    private PreviewView cameraPreviewView;
    private LinearLayout voiceUIGroup;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider mCameraProvider;
    // Add this with your other class member variables (e.g., near currentCaptureMode)
    private String currentOcrTtsLanguageCode = AzureConfig.Speech.EN_LANG_CODE; // Default to English

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pollingHandler = new Handler(Looper.getMainLooper());
        initializeComponents();
        requestPermissions();
        setupVoiceRecognition();

        cameraPreviewView = findViewById(R.id.cameraPreviewView);
        voiceUIGroup = findViewById(R.id.voiceUIGroup);
        cameraExecutor = Executors.newSingleThreadExecutor();
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                mCameraProvider = cameraProviderFuture.get();
            } catch (Exception e) {
                Log.e(TAG, "Error getting CameraProvider instance", e);
            }
        }, ContextCompat.getMainExecutor(this));

        currentCaptureMode = CAPTURE_MODE_OCR;

        boolean canSpeakWelcome = !AzureConfig.AZURE_SPEECH_KEY.isEmpty() &&
                !AzureConfig.AZURE_SPEECH_KEY.equals("YOUR_AZURE_SPEECH_KEY_HERE");
        if (canSpeakWelcome) {
            speakText(getString(R.string.welcome_message), null, null);
        } else {
            Log.w(TAG, "Azure Speech Key not configured. Cannot speak welcome message via Azure.");
            Toast.makeText(this, "Azure Speech Key not set, welcome message skipped.", Toast.LENGTH_SHORT).show();
        }

        if (!AzureConfig.isConfigValid()) {
            Log.w(TAG, "Overall Azure Config Status: " + AzureConfig.getConfigStatus());
        }
    }

    private void initializeComponents() {
        mainButton = findViewById(R.id.mainButton);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(AzureConfig.CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(AzureConfig.READ_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(AzureConfig.WRITE_TIMEOUT, TimeUnit.SECONDS)
                .build();
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
            if (isListening) {
                isListening = false;
                mainButton.setText(R.string.tap_to_speak);
            }
            mp.reset();
            return true;
        });
        mediaPlayer.setOnCompletionListener(mp -> Log.d(TAG, "MediaPlayer playback completed (default listener)."));

        mainButton.setOnClickListener(v -> {
            if (!isListening) {
                startListeningSequence();
            } else {
                stopListening();
            }
        });
        mainButton.setTextSize(24);
        objectAnalyzer = new ObjectAnalyzer(httpClient, this);
    }

    private void requestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        String[] basePermissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };

        for (String permission : basePermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION);
            }
        }

        if (ContextCompat.checkSelfPermission(this, "com.google.android.gms.permission.ACTIVITY_RECOGNITION") != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add("com.google.android.gms.permission.ACTIVITY_RECOGNITION");
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), REQUEST_PERMISSIONS_CODE);
        }
    }

    private void setupVoiceRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available.");
            speakText("Sorry, speech recognition is not available on this device.", null, null);
            return;
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                Log.d(TAG, "SpeechRecognizer is ready (onReadyForSpeech).");
            }

            @Override
            public void onBeginningOfSpeech() {
                Log.d(TAG, "SpeechRecognizer: onBeginningOfSpeech");
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                Log.d(TAG, "SpeechRecognizer: onEndOfSpeech");
            }

            @Override
            public void onError(int error) {
                isListening = false;
                mainButton.setText(R.string.tap_to_speak);
                Log.e(TAG, "Speech recognition error: " + getErrorMessage(error));
                speakText(getString(R.string.error_speech_recognition), null, null);
            }

            @Override
            public void onResults(Bundle results) {
                isListening = false;
                mainButton.setText(R.string.tap_to_speak);
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String command = matches.get(0).toLowerCase(Locale.ROOT).trim();
                    Log.d(TAG, "Voice command: " + command);
                    processVoiceCommand(command);
                } else {
                    Log.d(TAG, "No voice command matches found.");
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
    }

    private void processVoiceCommand(String command) {
        if (command.contains("read kannada") || command.contains("read kannada text")) {
            currentCaptureMode = CAPTURE_MODE_OCR;
            currentOcrTtsLanguageCode = AzureConfig.Speech.KN_LANG_CODE; // Set for Kannada TTS
            // You might want a more specific string resource for this prompt
            speakText(getString(R.string.camera_opening) + " for Kannada text.", null, this::startCameraPreview);
        }
        else if (command.contains("read this") || command.contains("read text")) {
            currentCaptureMode = CAPTURE_MODE_OCR;
            speakText(getString(R.string.camera_opening), null, this::startCameraPreview);
        } else if (command.contains("where am i") || command.contains("location")) {
            getLocationAndPoiInfo();
        } else if (command.contains("what\'s around me") || command.contains("describe scene") || command.contains("what is this")) {
            currentCaptureMode = CAPTURE_MODE_SCENE_ANALYSIS;
            speakText(getString(R.string.scene_analysis_prompt), null, this::startCameraPreview);
        } else if (command.contains("identify currency") || command.contains("recognize money")) { // New command
            currentCaptureMode = CAPTURE_MODE_CURRENCY;
            speakText(getString(R.string.currency_recognition_prompt), null, this::startCameraPreview);
        } else if (command.contains("analyse") || command.contains("describe this item") || command.contains("what is this thing")) { // New command
            currentCaptureMode = CAPTURE_MODE_OBJECT_DETAIL;
            speakText(getString(R.string.object_analysis_prompt), null, this::startCameraPreview);
        } else {
            speakText(getString(R.string.command_help), null, null);
        }
    }

    private void startCameraPreview() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            speakText("I need camera permission.", null, null);
            return;
        }

        if (mCameraProvider == null) {
            cameraProviderFuture.addListener(() -> {
                try {
                    mCameraProvider = cameraProviderFuture.get();
                    if (mCameraProvider != null) {
                        bindCameraUseCases(mCameraProvider);
                        runOnUiThread(() -> {
                            cameraPreviewView.setVisibility(View.VISIBLE);
                            voiceUIGroup.setVisibility(View.GONE);
                        });
                    } else {
                        Log.e(TAG, "CameraProvider is null even after listener.");
                        speakText("Failed to initialize camera provider.", null, null);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error starting camera preview from future", e);
                    speakText("Failed to start camera.", null, null);
                }
            }, ContextCompat.getMainExecutor(this));
        } else {
            bindCameraUseCases(mCameraProvider);
            runOnUiThread(() -> {
                cameraPreviewView.setVisibility(View.VISIBLE);
                voiceUIGroup.setVisibility(View.GONE);
            });
        }
        cameraPreviewView.setOnClickListener(v -> takePicture());
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void bindCameraUseCases(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();
        preview.setSurfaceProvider(cameraPreviewView.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
                .setTargetRotation(cameraPreviewView.getDisplay().getRotation())
                .build();

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, preview, imageCapture);
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
            speakText("Failed to bind camera use cases.", null, null);
        }
    }

    private void takePicture() {
        if (imageCapture == null) {
            Log.e(TAG, "ImageCapture use case is null. Cannot take picture.");
            speakText("Error preparing camera for capture.", null, null);
            return;
        }

        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                Log.d(TAG, "CameraX: Image captured successfully.");
                Bitmap bitmap = imageProxyToBitmap(imageProxy); // Your existing conversion
                imageProxy.close(); // Close the ImageProxy as soon as you have the bitmap

                // --- Start of Additions/Modifications ---

                runOnUiThread(() -> { // Move UI updates that don't depend on the bitmap here
                    cameraPreviewView.setVisibility(View.GONE);
                    voiceUIGroup.setVisibility(View.VISIBLE);
                    if (mCameraProvider != null) {
                        mCameraProvider.unbindAll(); // Unbind camera early if possible
                    }
                });

                if (bitmap != null) {
                    // --- Add this block for debugging OCR ---
                    try {
                        File path = getExternalFilesDir(null); // Or getCacheDir()
                        // Ensure path exists if using getExternalFilesDir (though it usually does)
                        if (path != null && !path.exists()) {
                            path.mkdirs();
                        }
                        File file = new File(path, "ocr_debug_image_" + System.currentTimeMillis() + ".jpg");
                        FileOutputStream out = new FileOutputStream(file);
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
                        out.flush();
                        out.close();
                        Log.d(TAG, "Debug image saved to: " + file.getAbsolutePath());
                    } catch (Exception e) { // Catch generic Exception for broader safety here
                        Log.e(TAG, "Error saving debug image", e);
                    }
                    // --- End of debug block ---

                    // Now proceed with processing based on capture mode
                    if (currentCaptureMode == CAPTURE_MODE_OCR) {
                        // Consider if "image_captured_processing" should be spoken before or after a slight delay
                        // or if the polling start message is sufficient.
                        // For now, let processImageWithAzureVision handle its own "processing" messages.
                        processImageWithAzureVision(bitmap);
                    } else if (currentCaptureMode == CAPTURE_MODE_SCENE_ANALYSIS) {
                        speakText(getString(R.string.analyzing_scene), null, null);
                        analyzeSceneWithAzureAI(bitmap);
                    } else if (currentCaptureMode == CAPTURE_MODE_CURRENCY) {
                        // speakText(getString(R.string.image_captured_processing), null, null); // Already handled by processImageForCurrency or its poll start
                        processImageForCurrency(bitmap);
                    } else if (currentCaptureMode == CAPTURE_MODE_OBJECT_DETAIL) {
                        runOnUiThread(() -> {
                            mainButton.setEnabled(false);
                            mainButton.setText(getString(R.string.analyzing_object_button));
                        });
                        objectAnalyzer.analyzeImageDetails(bitmap, new ObjectAnalyzer.ObjectAnalysisCallback() {
                            @Override
                            public void onObjectAnalysisSuccess(String description) {
                                runOnUiThread(() -> {
                                    speakText(description, null, null);
                                    mainButton.setEnabled(true);
                                    mainButton.setText(R.string.tap_to_speak);
                                });
                            }

                            @Override
                            public void onObjectAnalysisError(String errorMessage) {
                                runOnUiThread(() -> {
                                    speakText(errorMessage, null, null);
                                    mainButton.setEnabled(true);
                                    mainButton.setText(R.string.tap_to_speak);
                                });
                            }
                        });
                    }
                } else { // Bitmap is null
                    Log.e(TAG, "Failed to convert ImageProxy to Bitmap.");
                    speakText("Failed to process captured image.", null, null);
                    runOnUiThread(() -> { // Ensure UI is updated correctly on failure
                        mainButton.setEnabled(true);
                        mainButton.setText(R.string.tap_to_speak);
                        // Ensure camera UI is reset if it wasn't already
                        cameraPreviewView.setVisibility(View.GONE);
                        voiceUIGroup.setVisibility(View.VISIBLE);
                        if (mCameraProvider != null) {
                            mCameraProvider.unbindAll();
                        }
                    });
                }
                // --- End of Additions/Modifications ---
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "CameraX: Image capture failed", exception);
                runOnUiThread(() -> {
                    cameraPreviewView.setVisibility(View.GONE);
                    voiceUIGroup.setVisibility(View.VISIBLE);
                    if (mCameraProvider != null) {
                        mCameraProvider.unbindAll();
                    }
                    speakText("Failed to capture image: " + exception.getMessage(), null, null);
                    // Ensure button is re-enabled and text reset if an error occurs during capture
                    mainButton.setEnabled(true);
                    mainButton.setText(R.string.tap_to_speak);
                });
            }
        });
    }



    private void analyzeSceneWithAzureAI(Bitmap bitmap) {
        Log.d(TAG, "Analyzing scene with Azure AI Vision v3.2...");
        runOnUiThread(() -> {
            mainButton.setEnabled(false);
            mainButton.setText(getString(R.string.analyzing_scene_button));
        });

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
        byte[] imageBytes = baos.toByteArray();
        RequestBody body = RequestBody.create(imageBytes, MediaType.parse("application/octet-stream"));

        Request request = new Request.Builder()
                .url(AzureConfig.AnalyzeScene.getAnalyzeSceneUrl())
                .addHeader("Ocp-Apim-Subscription-Key", AzureConfig.AZURE_VISION_KEY)
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Azure Scene Analysis API (v3.2) call failed", e);
                speakText(getString(R.string.error_scene_analysis), null, null);
                runOnUiThread(() -> {
                    mainButton.setEnabled(true);
                    mainButton.setText(R.string.tap_to_speak);
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                final String responseBodyString = response.body() != null ? response.body().string() : "null";
                if (response.isSuccessful()) {
                    Log.d(TAG, "Azure Scene Analysis API (v3.2) success. Response: " + responseBodyString);
                    try {
                        JSONObject jsonObject = new JSONObject(responseBodyString);
                        String caption = null;
                        if (jsonObject.has("description")) {
                            JSONObject descriptionObj = jsonObject.getJSONObject("description");
                            if (descriptionObj.has("captions")) {
                                JSONArray captionsArray = descriptionObj.getJSONArray("captions");
                                if (captionsArray.length() > 0 && captionsArray.getJSONObject(0).has("text")) {
                                    caption = captionsArray.getJSONObject(0).getString("text");
                                }
                            }
                        }

                        List<String> objectNames = new ArrayList<>();
                        if (jsonObject.has("objects")) {
                            JSONArray objectsArray = jsonObject.getJSONArray("objects");
                            for (int i = 0; i < objectsArray.length(); i++) {
                                JSONObject objectItem = objectsArray.getJSONObject(i);
                                if (objectItem.has("object")) {
                                    objectNames.add(objectItem.getString("object"));
                                }
                            }
                        }

                        StringBuilder narration = new StringBuilder();
                        narration.append(getString(R.string.scene_analysis_success_intro));

                        if (caption != null && !caption.isEmpty()) {
                            narration.append(caption).append(".");
                        } else {
                            narration.append(getString(R.string.scene_analysis_no_description));
                        }

                        if (!objectNames.isEmpty()) {
                            narration.append(getString(R.string.scene_analysis_also_see));
                            for (int i = 0; i < Math.min(objectNames.size(), 3); i++) {
                                narration.append(objectNames.get(i));
                                if (i < Math.min(objectNames.size(), 3) - 1) {
                                    narration.append(", ");
                                } else {
                                    narration.append(".");
                                }
                            }
                        } else if (caption == null || caption.isEmpty()) {
                            narration.append(getString(R.string.scene_analysis_no_objects));
                        }

                        speakText(narration.toString(), null, null);

                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing Scene Analysis (v3.2) JSON response", e);
                        speakText(getString(R.string.error_scene_analysis_parsing), null, null);
                    }
                } else {
                    Log.e(TAG, "Azure Scene Analysis API (v3.2) error: " + response.code() + " Body: " + responseBodyString);
                    speakText(String.format(getString(R.string.error_scene_analysis_with_code), response.code()), null, null);
                }
                runOnUiThread(() -> {
                    mainButton.setEnabled(true);
                    mainButton.setText(R.string.tap_to_speak);
                });
            }
        });
    }

    private Bitmap imageProxyToBitmap(ImageProxy image) {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            Log.w(TAG, "ImageProxy format not YUV_420_888: " + image.getFormat() + ". Attempting direct JPEG decode.");
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) {
                Log.e(TAG, "Direct JPEG decode failed for format: " + image.getFormat());
            }
            return bitmap;
        }

        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), image.getHeight()), 90, out);
        byte[] imageBytes = out.toByteArray();
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    private void startListeningSequence() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            speakText("I need microphone permission to listen.", null, null);
            return;
        }
        if (speechRecognizer == null) {
            Log.e(TAG, "SpeechRecognizer not initialized!");
            return;
        }
        isListening = true;
        mainButton.setText(R.string.listening);
        speakText(getString(R.string.listening_prompt), null, this::actualStartAndroidSpeechRecognizer);
    }

    private void actualStartAndroidSpeechRecognizer() {
        if (!isListening) {
            Log.d(TAG, "Listening cancelled before prompt finished.");
            return;
        }
        Log.d(TAG, "Prompt finished, now starting Android SpeechRecognizer.");
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        speechRecognizer.startListening(intent);
    }

    private void stopListening() {
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
        isListening = false;
        mainButton.setText(R.string.tap_to_speak);
        Log.d(TAG, "stopListening called, UI updated.");
    }

    private void processImageWithAzureVision(Bitmap bitmap) {
        pollingHandler.removeCallbacksAndMessages(null);
        Log.d(TAG, "Processing image with Azure Read API...");
        runOnUiThread(() -> {
            mainButton.setEnabled(false);
            mainButton.setText(R.string.processing_image);
        });

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
        RequestBody body = RequestBody.create(baos.toByteArray(), MediaType.parse("application/octet-stream"));
        Request request = new Request.Builder()
                .url(AzureConfig.ReadAPI.getReadAnalyzeUrl())
                .addHeader("Ocp-Apim-Subscription-Key", AzureConfig.AZURE_VISION_KEY)
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Azure Read API (analyze) call failed", e);
                runOnUiThread(() -> {
                    speakText(getString(R.string.error_image_processing), null, null);
                    mainButton.setEnabled(true);
                    mainButton.setText(R.string.tap_to_speak);
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.code() == 202) {
                    String operationLocationUrl = response.header("Operation-Location");
                    if (operationLocationUrl != null && !operationLocationUrl.isEmpty()) {
                        Log.d(TAG, "Read API analyze started. Operation URL: " + operationLocationUrl);
                        runOnUiThread(() -> speakText(getString(R.string.image_submitted_wait), null, null));
                        pollReadApiResults(operationLocationUrl, 0);
                    } else {
                        Log.e(TAG, "Azure Read API (analyze) error: Missing Operation-Location header.");
                        runOnUiThread(() -> {
                            speakText(getString(R.string.error_image_processing) + " (Missing Op-Location)", null, null);
                            mainButton.setEnabled(true);
                            mainButton.setText(R.string.tap_to_speak);
                        });
                    }
                } else {
                    final int responseCode = response.code();
                    final String responseBodyString = response.body() != null ? response.body().string() : "null";
                    Log.e(TAG, "Azure Read API (analyze) error: " + responseCode + " Body: " + responseBodyString);
                    runOnUiThread(() -> {
                        speakText(getString(R.string.error_image_processing) + " (Code: " + responseCode + ")", null, null);
                        mainButton.setEnabled(true);
                        mainButton.setText(R.string.tap_to_speak);
                    });
                }
            }
        });
    }

    private void pollReadApiResults(final String operationLocationUrl, final int attemptCount) {
        if (attemptCount >= AzureConfig.ReadAPI.MAX_POLLING_ATTEMPTS) {
            Log.e(TAG, "Azure Read API polling timed out after " + attemptCount + " attempts.");
            runOnUiThread(() -> {
                speakText(getString(R.string.error_image_processing_timeout), null, null);
                mainButton.setEnabled(true);
                mainButton.setText(R.string.tap_to_speak);
            });
            return;
        }

        Log.d(TAG, "Polling Read API results (Attempt " + (attemptCount + 1) + "): " + operationLocationUrl);
        Request request = new Request.Builder()
                .url(operationLocationUrl)
                .addHeader("Ocp-Apim-Subscription-Key", AzureConfig.AZURE_VISION_KEY)
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Azure Read API (polling) call failed", e);
                runOnUiThread(() -> {
                    speakText(getString(R.string.error_image_processing_polling), null, null);
                    mainButton.setEnabled(true);
                    mainButton.setText(R.string.tap_to_speak);
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                final String responseBodyString = response.body() != null ? response.body().string() : "null";
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Azure Read API (polling) error: " + response.code() + " Body: " + responseBodyString);
                    runOnUiThread(() -> {
                        speakText(getString(R.string.error_image_processing_polling) + " (Code: " + response.code() + ")", null, null);
                        mainButton.setEnabled(true);
                        mainButton.setText(R.string.tap_to_speak);
                    });
                    return;
                }

                try {
                    JSONObject jsonObject = new JSONObject(responseBodyString);
                    String status = jsonObject.optString("status").toLowerCase(Locale.ROOT);
                    Log.d(TAG, "Read API polling status: " + status);

                    if (status.equals("succeeded")) {
                        parseAndSpeakReadApiResult(responseBodyString);
                        runOnUiThread(() -> {
                            mainButton.setEnabled(true);
                            mainButton.setText(R.string.tap_to_speak);
                        });
                    } else if (status.equals("running") || status.equals("notstarted")) {
                        pollingHandler.postDelayed(() -> pollReadApiResults(operationLocationUrl, attemptCount + 1),
                                attemptCount == 0 ? AzureConfig.ReadAPI.POLLING_INITIAL_DELAY_MS : AzureConfig.ReadAPI.POLLING_INTERVAL_MS);
                    } else if (status.equals("failed")) {
                        Log.e(TAG, "Azure Read API processing failed. Response: " + responseBodyString);
                        runOnUiThread(() -> {
                            speakText(getString(R.string.error_text_processing_failed), null, null);
                            mainButton.setEnabled(true);
                            mainButton.setText(R.string.tap_to_speak);
                        });
                    } else {
                        Log.w(TAG, "Azure Read API unknown status: " + status + ". Response: " + responseBodyString);
                        runOnUiThread(() -> {
                            speakText(getString(R.string.error_image_processing_unknown) + " (Status: " + status + ")", null, null);
                            mainButton.setEnabled(true);
                            mainButton.setText(R.string.tap_to_speak);
                        });
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Error parsing Read API polling response", e);
                    runOnUiThread(() -> {
                        speakText(getString(R.string.error_text_processing), null, null);
                        mainButton.setEnabled(true);
                        mainButton.setText(R.string.tap_to_speak);
                    });
                }
            }
        });
    }

    // Inside MainActivity.java

// (Keep currentOcrTtsLanguageCode as a class variable, defaulting to English)
// private String currentOcrTtsLanguageCode = AzureConfig.Speech.EN_LANG_CODE;

// processVoiceCommand would still set currentOcrTtsLanguageCode for explicit commands
// e.g., "read this in kannada" sets it to KN_LANG_CODE
//       "read this" (generic) could reset it to a "auto" mode or rely on parsing below.

    private void parseAndSpeakReadApiResult(String jsonResponse) {
        StringBuilder extractedTextBuilder = new StringBuilder();
        String languageToUseForTTS = AzureConfig.Speech.EN_LANG_CODE; // Default to English

        // Check if the user made an explicit language choice for this OCR session
        if (currentOcrTtsLanguageCode != null && !currentOcrTtsLanguageCode.equals(AzureConfig.Speech.EN_LANG_CODE)) {
            languageToUseForTTS = currentOcrTtsLanguageCode; // User's explicit choice (e.g., Kannada)
        } else {
            // No explicit user choice for this session (or it was reset to default English),
            // so try to get the language detected by Azure OCR.
            try {
                JSONObject rootObject = new JSONObject(jsonResponse);
                JSONObject analyzeResult = rootObject.optJSONObject("analyzeResult");
                if (analyzeResult != null) {
                    JSONArray readResults = analyzeResult.optJSONArray("readResults");
                    if (readResults != null && readResults.length() > 0) {
                        JSONObject firstPage = readResults.getJSONObject(0);
                        if (firstPage.has("language")) {
                            String azureDetectedLang = firstPage.getString("language");
                            if (azureDetectedLang != null && !azureDetectedLang.isEmpty()) {
                                languageToUseForTTS = azureDetectedLang.split("-")[0]; // "kn", "en", etc.
                                Log.d(TAG, "Azure OCR detected language: " + languageToUseForTTS);
                            }
                        }
                    }
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing language from Azure Read API response, defaulting TTS to English.", e);
                // languageToUseForTTS remains English (default)
            }
        }

        // Now, parse the actual text (this part is outside the try-catch for language detection)
        try {
            JSONObject rootObject = new JSONObject(jsonResponse);
            JSONObject analyzeResult = rootObject.optJSONObject("analyzeResult");
            if (analyzeResult == null) {
                Log.e(TAG, "Read API: analyzeResult is missing.");
                speakText(getString(R.string.error_text_processing) + " (No analyzeResult)", null, null);
                runOnUiThread(() -> { mainButton.setEnabled(true); mainButton.setText(R.string.tap_to_speak); });
                return;
            }
            JSONArray readResults = analyzeResult.optJSONArray("readResults");
            if (readResults == null || readResults.length() == 0) {
                Log.w(TAG, "Read API: readResults is empty or missing.");
                speakText(getString(R.string.no_text_found), languageToUseForTTS, null); // Use determined lang
                runOnUiThread(() -> { mainButton.setEnabled(true); mainButton.setText(R.string.tap_to_speak); });
                return;
            }
            for (int r = 0; r < readResults.length(); r++) {
                JSONObject pageResult = readResults.getJSONObject(r);
                JSONArray lines = pageResult.optJSONArray("lines");
                if (lines != null) {
                    for (int i = 0; i < lines.length(); i++) {
                        extractedTextBuilder.append(lines.getJSONObject(i).getString("text")).append(" ");
                    }
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing text from Read API final response", e);
            speakText(getString(R.string.error_text_processing) + " (JSON Parse Fail)", null, null);
            runOnUiThread(() -> { mainButton.setEnabled(true); mainButton.setText(R.string.tap_to_speak); });
            return;
        }

        final String finalText = extractedTextBuilder.toString().trim();
        final String finalLanguageForTTS = languageToUseForTTS; // Use the language determined above

        runOnUiThread(() -> {
            if (finalText.isEmpty()) {
                speakText(getString(R.string.no_text_found), finalLanguageForTTS, null);
            } else {
                Log.d(TAG, "Read API Result: \"" + finalText + "\" (Effective TTS lang: " + finalLanguageForTTS + ")");
                speakText(getString(R.string.text_found) + " " + finalText, finalLanguageForTTS, null);
            }
            mainButton.setEnabled(true);
            mainButton.setText(R.string.tap_to_speak);
        });
    }


    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon/2) * Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R * c;
    }

    private void getLocationAndPoiInfo() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) ||
            ContextCompat.checkSelfPermission(this, "com.google.android.gms.permission.ACTIVITY_RECOGNITION") != PackageManager.PERMISSION_GRANTED) {

            speakText("I need location and activity recognition permissions to tell you where you are.", null, null);
            return;
        }

        mainButton.setEnabled(false);
        mainButton.setText(R.string.processing_location);
        speakText(getString(R.string.getting_location_details), null, null);

        fusedLocationClient.getLastLocation()
            .addOnSuccessListener(this, location -> {
                if (location != null) {
                    currentLatitude = location.getLatitude();
                    currentLongitude = location.getLongitude();
                    Log.d(TAG, "Current location: Lat=" + currentLatitude + ", Lon=" + currentLongitude);

                    fetchedAddress = null;
                    busStopName = null;
                    busStopDistance = -1;
                    railwayStationName = null;
                    railwayStationDistance = -1;
                    pendingLocationRequests = 3;

                    fetchReverseGeocodedAddress(currentLatitude, currentLongitude);
                    fetchPoiDetails(currentLatitude, currentLongitude, "bus stop", true);
                    fetchPoiDetails(currentLatitude, currentLongitude, "railway station", false);
                } else {
                    Log.e(TAG, "FusedLocationClient: Last location is null.");
                    speakText(getString(R.string.error_location), null, null);
                    mainButton.setEnabled(true);
                    mainButton.setText(R.string.tap_to_speak);
                }
            })
            .addOnFailureListener(this, e -> {
                Log.e(TAG, "Error getting location from FusedLocationClient", e);
                speakText(getString(R.string.error_location_access), null, null);
                mainButton.setEnabled(true);
                mainButton.setText(R.string.tap_to_speak);
            });
    }

    private void fetchReverseGeocodedAddress(double latitude, double longitude) {
        String url = AzureConfig.Maps.getReverseGeocodingUrl(latitude, longitude);
        Log.d(TAG, "Fetching reverse geocoded address: " + url);
        Request request = new Request.Builder().url(url).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Azure Maps Reverse Geocoding API failed", e);
                fetchedAddress = "Error fetching address.";
                onLocationPartFetched();
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                final String responseBodyString = response.body() != null ? response.body().string() : null;
                if (response.isSuccessful() && responseBodyString != null) {
                    try {
                        JSONObject jsonObject = new JSONObject(responseBodyString);
                        JSONArray addresses = jsonObject.optJSONArray("addresses");
                        if (addresses != null && addresses.length() > 0) {
                            JSONObject firstAddress = addresses.getJSONObject(0).optJSONObject("address");
                            if (firstAddress != null) {
                                fetchedAddress = firstAddress.optString("freeformAddress", "Address not found.");
                                Log.d(TAG, "Fetched address: " + fetchedAddress);
                            } else {
                                fetchedAddress = "Address details not found in response.";
                            }
                        } else {
                            fetchedAddress = "No addresses found in response.";
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing reverse geocoding response", e);
                        fetchedAddress = "Error parsing address data.";
                    }
                } else {
                    Log.e(TAG, "Azure Maps Reverse Geocoding API error: " + response.code() + " Body: " + responseBodyString);
                    fetchedAddress = "Could not retrieve address (Code: " + response.code() + ")";
                }
                onLocationPartFetched();
            }
        });
    }

    private void fetchPoiDetails(double latitude, double longitude, String poiCategory, final boolean isBusStop) {
        String url = AzureConfig.Maps.getNearbySearchPoiUrl(latitude, longitude, poiCategory);
        Log.d(TAG, "Fetching POI details for " + poiCategory + ": " + url);
        Request request = new Request.Builder().url(url).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Azure Maps POI (" + poiCategory + ") API failed", e);
                if (isBusStop) {
                    busStopName = "Error fetching bus stop.";
                    busStopDistance = -2;
                } else {
                    railwayStationName = "Error fetching railway station.";
                    railwayStationDistance = -2;
                }
                onLocationPartFetched();
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                final String responseBodyString = response.body() != null ? response.body().string() : null;
                if (response.isSuccessful() && responseBodyString != null) {
                    try {
                        JSONObject jsonObject = new JSONObject(responseBodyString);
                        JSONArray results = jsonObject.optJSONArray("results");
                        if (results != null && results.length() > 0) {
                            JSONObject firstResult = results.getJSONObject(0);
                            String name = firstResult.optJSONObject("poi").optString("name", "Unnamed " + poiCategory);
                            JSONObject position = firstResult.optJSONObject("position");
                            double poiLat = position.optDouble("lat");
                            double poiLon = position.optDouble("lon");
                            double distance = haversine(currentLatitude, currentLongitude, poiLat, poiLon);

                            if (isBusStop) {
                                busStopName = name;
                                busStopDistance = distance;
                                Log.d(TAG, "Fetched Bus Stop: " + name + ", Dist: " + distance);
                            } else {
                                railwayStationName = name;
                                railwayStationDistance = distance;
                                Log.d(TAG, "Fetched Railway Station: " + name + ", Dist: " + distance);
                            }
                        } else {
                            Log.w(TAG, "No " + poiCategory + " found nearby.");
                            if (isBusStop) {
                                busStopName = "No bus stop found nearby";
                                busStopDistance = -1;
                            } else {
                                railwayStationName = "No railway station found nearby";
                                railwayStationDistance = -1;
                            }
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing POI (" + poiCategory + ") response", e);
                        if (isBusStop) {
                            busStopName = "Error parsing bus stop data.";
                            busStopDistance = -2;
                        } else {
                            railwayStationName = "Error parsing railway station data.";
                            railwayStationDistance = -2;
                        }
                    }
                } else {
                    Log.e(TAG, "Azure Maps POI (" + poiCategory + ") API error: " + response.code() + " Body: " + responseBodyString);
                     if (isBusStop) {
                        busStopName = "Could not retrieve bus stop (Code: " + response.code() + ")";
                        busStopDistance = -2;
                    } else {
                        railwayStationName = "Could not retrieve railway station (Code: " + response.code() + ")";
                        railwayStationDistance = -2;
                    }
                }
                onLocationPartFetched();
            }
        });
    }

    private synchronized void onLocationPartFetched() {
        pendingLocationRequests--;
        Log.d(TAG, "Location part fetched. Pending requests: " + pendingLocationRequests);
        if (pendingLocationRequests == 0) {
            Log.d(TAG, "All location parts fetched. Combining and speaking.");
            combineAndSpeakLocationPois();
             runOnUiThread(() -> {
                mainButton.setEnabled(true);
                mainButton.setText(R.string.tap_to_speak);
            });
        }
    }

    private void combineAndSpeakLocationPois() {
        StringBuilder narration = new StringBuilder();

        if (fetchedAddress != null && !fetchedAddress.toLowerCase().contains("error")) {
            narration.append(getString(R.string.location_prefix)).append(fetchedAddress).append(". ");
        } else if (fetchedAddress != null) {
            narration.append(fetchedAddress).append(". ");
        } else {
            narration.append("Current address could not be determined. ");
        }

        if (busStopName != null && busStopDistance >= 0) {
            narration.append("Nearest bus stop: ").append(busStopName).append(", approximately ")
                     .append(String.format(Locale.US, "%.0f", busStopDistance)).append(" meters away. ");
        } else if (busStopName != null) {
             narration.append(busStopName).append(". ");
        }

        if (railwayStationName != null && railwayStationDistance >= 0) {
            narration.append("Nearest railway station: ").append(railwayStationName).append(", approximately ")
                     .append(String.format(Locale.US, "%.0f", railwayStationDistance)).append(" meters away. ");
        } else if (railwayStationName != null) {
            narration.append(railwayStationName).append(". ");
        }

        String finalNarration = narration.toString().trim();
        if (finalNarration.isEmpty()) {
            speakText("Sorry, I couldn\'t retrieve the full location details.", null, null);
        } else {
            speakText(finalNarration, null, null);
        }
    }

    private void speakText(String textToSpeak) {
        speakText(textToSpeak, null, null);
    }

    private void speakText(String textToSpeak, @Nullable String languageHint) {
        speakText(textToSpeak, languageHint, null);
    }

    private void speakText(String textToSpeak, @Nullable String languageHint, @Nullable Runnable onPlaybackCompleteAction) {
        if (textToSpeak == null || textToSpeak.isEmpty()) {
            Log.w(TAG, "speakText called with null or empty text.");
            if (onPlaybackCompleteAction != null) {
                if (isListening) {
                    isListening = false;
                    mainButton.setText(R.string.tap_to_speak);
                }
                 onPlaybackCompleteAction.run();
            }
            return;
        }
        if (AzureConfig.AZURE_SPEECH_KEY.isEmpty() || AzureConfig.AZURE_SPEECH_KEY.equals("YOUR_AZURE_SPEECH_KEY_HERE")) {
            Log.e(TAG, "Azure Speech Key is not configured. Cannot speak.");
            Toast.makeText(this, "Azure TTS not configured (key missing).", Toast.LENGTH_SHORT).show();
             if (onPlaybackCompleteAction != null) {
                if (isListening) {
                    isListening = false;
                    mainButton.setText(R.string.tap_to_speak);
                }
                 onPlaybackCompleteAction.run();
            }
            return;
        }

        String langCodeForSSML;
        String voiceNameForSSML;
        String langHintNormalized = languageHint != null ? languageHint.toLowerCase(Locale.ROOT) : "";

        // Use startsWith for broader matching (e.g.,
        // Use startsWith for broader matching (e.g., "kn-IN" starts with "kn")
        if (langHintNormalized.startsWith("hi")) {
            langCodeForSSML = AzureConfig.Speech.HI_LANG_CODE; // "hi-IN"
            voiceNameForSSML = AzureConfig.Speech.HI_VOICE_NAME;
        } else if (langHintNormalized.startsWith("kn")) {
            langCodeForSSML = AzureConfig.Speech.KN_LANG_CODE; // "kn-IN"
            voiceNameForSSML = AzureConfig.Speech.KN_VOICE_NAME;
        } else { // Default to English if no match or hint is "en" or null/empty
            langCodeForSSML = AzureConfig.Speech.EN_LANG_CODE; // "en-US"
            voiceNameForSSML = AzureConfig.Speech.EN_VOICE_NAME;
        }
        Log.d(TAG, "Speaking text: '" + textToSpeak.substring(0, Math.min(textToSpeak.length(), 50)) + "...' with lang: " + langCodeForSSML);

        String escapedText = Html.escapeHtml(textToSpeak);
        String ssml = String.format(Locale.US, AzureConfig.Speech.DYNAMIC_SSML_TEMPLATE, langCodeForSSML, voiceNameForSSML, escapedText);
        RequestBody body = RequestBody.create(ssml, MediaType.parse("application/ssml+xml"));
        Request request = new Request.Builder()
                .url(AzureConfig.Speech.getTtsUrl())
                .addHeader("Ocp-Apim-Subscription-Key", AzureConfig.AZURE_SPEECH_KEY)
                .addHeader("Content-Type", "application/ssml+xml")
                .addHeader("X-Microsoft-OutputFormat", AzureConfig.Speech.OUTPUT_FORMAT)
                .addHeader("User-Agent", "VoiceAssistantApp/1.0")
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Azure TTS API call failed", e);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Azure TTS failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                if (onPlaybackCompleteAction != null) {
                     if (isListening) {
                        isListening = false;
                        mainButton.setText(R.string.tap_to_speak);
                    }
                     onPlaybackCompleteAction.run();
                }
            }
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    playAudioStream(response.body().byteStream(), onPlaybackCompleteAction);
                } else {
                    String errorBody = response.body() != null ? response.body().string() : "null";
                    Log.e(TAG, "Azure TTS API error: " + response.code() + " - " + response.message() + " Body: " + errorBody);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Azure TTS Error: " + response.code(), Toast.LENGTH_LONG).show());
                     if (onPlaybackCompleteAction != null) {
                        if (isListening) {
                            isListening = false;
                            mainButton.setText(R.string.tap_to_speak);
                        }
                         onPlaybackCompleteAction.run();
                    }
                }
            }
        });
    }

    private void playAudioStream(InputStream audioStream, @Nullable Runnable onPlaybackCompleteAction) {
        try {
            File tempMp3 = File.createTempFile("azure_tts", ".mp3", getCacheDir());
            tempMp3.deleteOnExit();
            try (OutputStream out = new FileOutputStream(tempMp3); InputStream in = audioStream) {
                byte[] buffer = new byte[8 * 1024];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
            runOnUiThread(() -> {
                try {
                    if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                    mediaPlayer.reset();
                    mediaPlayer.setDataSource(tempMp3.getAbsolutePath());
                    mediaPlayer.setOnCompletionListener(mp -> {
                        Log.d(TAG, "MediaPlayer playback completed.");
                        if (onPlaybackCompleteAction != null) {
                            onPlaybackCompleteAction.run();
                        }
                    });
                    mediaPlayer.prepareAsync();
                    mediaPlayer.setOnPreparedListener(MediaPlayer::start);
                } catch (IOException | IllegalStateException e) {
                    Log.e(TAG, "MediaPlayer prepare/start failed for TTS audio", e);
                     if (onPlaybackCompleteAction != null) {
                        if (isListening) {
                            isListening = false;
                            mainButton.setText(R.string.tap_to_speak);
                        }
                         onPlaybackCompleteAction.run();
                    }
                }
            });
        } catch (IOException e) {
            Log.e(TAG, "Failed to process Azure TTS audio stream", e);
             if (onPlaybackCompleteAction != null) {
                if (isListening) {
                    isListening = false;
                    mainButton.setText(R.string.tap_to_speak);
                }
                onPlaybackCompleteAction.run();
            }
        }
    }

    private String getErrorMessage(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO: return "Audio recording error";
            case SpeechRecognizer.ERROR_CLIENT: return "Client side error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Insufficient permissions";
            case SpeechRecognizer.ERROR_NETWORK: return "Network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "Network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH: return "No match found";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "RecognitionService busy";
            case SpeechRecognizer.ERROR_SERVER: return "Server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "No speech input";
            default: return "Unknown speech error";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
        pollingHandler.removeCallbacksAndMessages(null);
        cameraExecutor.shutdown();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            boolean allGranted = true;
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    Log.w(TAG, "Permission denied: " + permissions[i]);
                }
            }
            if (!allGranted) {
                Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show();
                speakText(getString(R.string.permissions_grant), null, null);
            } else {
                Log.d(TAG, "All requested permissions granted.");
            }
        }
    }
    private void processImageForCurrency(Bitmap bitmap) {
        pollingHandler.removeCallbacksAndMessages(null);
        Log.d(TAG, "Processing image for Currency with Azure Read API...");
        runOnUiThread(() -> {
            mainButton.setEnabled(false);
            mainButton.setText(getString(R.string.identifying_currency_button)); // Use new string
        });

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
        RequestBody body = RequestBody.create(baos.toByteArray(), MediaType.parse("application/octet-stream"));
        Request request = new Request.Builder()
                .url(AzureConfig.ReadAPI.getReadAnalyzeUrl()) // Same Read API
                .addHeader("Ocp-Apim-Subscription-Key", AzureConfig.AZURE_VISION_KEY)
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Azure Read API (currency analyze) call failed", e);
                runOnUiThread(() -> {
                    speakText(getString(R.string.error_currency_processing), null, null);
                    mainButton.setEnabled(true);
                    mainButton.setText(R.string.tap_to_speak);
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.code() == 202) { // Accepted
                    String operationLocationUrl = response.header("Operation-Location");
                    if (operationLocationUrl != null && !operationLocationUrl.isEmpty()) {
                        Log.d(TAG, "Currency Read API analyze started. Operation URL: " + operationLocationUrl);
                        runOnUiThread(() -> speakText(getString(R.string.image_submitted_wait), null, null)); // Re-use
                        pollCurrencyReadApiResults(operationLocationUrl, 0);
                    } else {
                        Log.e(TAG, "Azure Read API (currency analyze) error: Missing Operation-Location header.");
                        runOnUiThread(() -> {
                            speakText(getString(R.string.error_currency_processing) + " (Missing Op-Location)", null, null);
                            mainButton.setEnabled(true);
                            mainButton.setText(R.string.tap_to_speak);
                        });
                    }
                } else {
                    final int responseCode = response.code();
                    final String responseBodyString = response.body() != null ? response.body().string() : "null";
                    Log.e(TAG, "Azure Read API (currency analyze) error: " + responseCode + " Body: " + responseBodyString);
                    runOnUiThread(() -> {
                        speakText(getString(R.string.error_currency_processing) + " (Code: " + responseCode + ")", null, null);
                        mainButton.setEnabled(true);
                        mainButton.setText(R.string.tap_to_speak);
                    });
                }
            }
        });
    }
    private void pollCurrencyReadApiResults(final String operationLocationUrl, final int attemptCount) {
        if (attemptCount >= AzureConfig.ReadAPI.MAX_POLLING_ATTEMPTS) {
            Log.e(TAG, "Azure Read API (currency) polling timed out after " + attemptCount + " attempts.");
            runOnUiThread(() -> {
                // Use the currency-specific timeout string
                speakText(getString(R.string.error_currency_processing_timeout), null, null);
                mainButton.setEnabled(true);
                mainButton.setText(R.string.tap_to_speak);
            });
            return;
        }

        Log.d(TAG, "Polling Currency Read API results (Attempt " + (attemptCount + 1) + "): " + operationLocationUrl);
        Request request = new Request.Builder()
                .url(operationLocationUrl)
                .addHeader("Ocp-Apim-Subscription-Key", AzureConfig.AZURE_VISION_KEY)
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Azure Read API (currency polling) call failed", e);
                runOnUiThread(() -> {
                    speakText(getString(R.string.error_currency_processing), null, null); // Generic error for now
                    mainButton.setEnabled(true);
                    mainButton.setText(R.string.tap_to_speak);
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                final String responseBodyString = response.body() != null ? response.body().string() : "null";
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Azure Read API (currency polling) error: " + response.code() + " Body: " + responseBodyString);
                    runOnUiThread(() -> {
                        speakText(getString(R.string.error_currency_processing) + " (Code: " + response.code() + ")", null, null);
                        mainButton.setEnabled(true);
                        mainButton.setText(R.string.tap_to_speak);
                    });
                    return;
                }

                try {
                    JSONObject jsonObject = new JSONObject(responseBodyString);
                    String status = jsonObject.optString("status").toLowerCase(Locale.ROOT);
                    Log.d(TAG, "Currency Read API polling status: " + status);

                    if (status.equals("succeeded")) {
                        parseAndAnnounceIndianCurrency(responseBodyString); // Call the currency specific parser
                        runOnUiThread(() -> {
                            mainButton.setEnabled(true);
                            mainButton.setText(R.string.tap_to_speak);
                        });
                    } else if (status.equals("running") || status.equals("notstarted")) {
                        pollingHandler.postDelayed(() -> pollCurrencyReadApiResults(operationLocationUrl, attemptCount + 1),
                                attemptCount == 0 ? AzureConfig.ReadAPI.POLLING_INITIAL_DELAY_MS : AzureConfig.ReadAPI.POLLING_INTERVAL_MS);
                    } else if (status.equals("failed")) {
                        Log.e(TAG, "Azure Read API (currency) processing failed. Response: " + responseBodyString);
                        runOnUiThread(() -> {
                            speakText(getString(R.string.error_text_processing_failed), null, null); // Can re-use
                            mainButton.setEnabled(true);
                            mainButton.setText(R.string.tap_to_speak);
                        });
                    } else {
                        Log.w(TAG, "Azure Read API (currency) unknown status: " + status + ". Response: " + responseBodyString);
                        runOnUiThread(() -> {
                            speakText(getString(R.string.error_image_processing_unknown) + " (Status: " + status + ")", null, null); // Can re-use
                            mainButton.setEnabled(true);
                            mainButton.setText(R.string.tap_to_speak);
                        });
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Error parsing Currency Read API polling response", e);
                    runOnUiThread(() -> {
                        speakText(getString(R.string.error_text_processing), null, null); // Can re-use
                        mainButton.setEnabled(true);
                        mainButton.setText(R.string.tap_to_speak);
                    });
                }
            }
        });
    }
    private void parseAndAnnounceIndianCurrency(String jsonReadApiResponse) {
        Log.d(TAG, "Parsing currency OCR response...");
        StringBuilder allText = new StringBuilder();

        try {
            JSONObject root = new JSONObject(jsonReadApiResponse);
            JSONObject analyzeResult = root.optJSONObject("analyzeResult");
            if (analyzeResult != null) {
                JSONArray readResults = analyzeResult.optJSONArray("readResults");
                if (readResults != null) {
                    for (int i = 0; i < readResults.length(); i++) {
                        JSONArray lines = readResults.getJSONObject(i).optJSONArray("lines");
                        if (lines != null) {
                            for (int j = 0; j < lines.length(); j++) {
                                allText.append(lines.getJSONObject(j).getString("text")).append(" ");
                            }
                        }
                    }
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing JSON for currency recognition", e);
            speakText("Error reading currency OCR results.", null, null);
            return;
        }

        String text = allText.toString().toLowerCase(Locale.ROOT);
        Log.d(TAG, "Full OCR text: " + text);

        // Keywords to confirm it's money
        boolean hasCurrencyWord = text.contains("rupee") || text.contains("rupees") ||
                text.contains("₹") || text.contains("rs") ||
                text.contains("रुप") || text.contains("रूप") || text.contains(" रुपए");

        // Order matters: check highest denomination first
        String detected = null;
        if (text.contains("2000")) detected = "2000";
        else if (text.contains("500")) detected = "500";
        else if (text.contains("200")) detected = "200";
        else if (text.contains("100")) detected = "100";
        else if (text.contains("50")) detected = "50";
        else if (text.contains("20")) detected = "20";
        else if (text.contains("10")) detected = "10";

        if (detected != null && hasCurrencyWord) {
            speakText("Detected " + detected + " rupees", null, null);
        } else if (detected != null) {
            // Fallback: denomination seen but no currency word
            speakText("Detected " + detected + " (possibly currency)", null, null);
        } else {
            speakText("Could not identify the currency note.", null, null);
        }
    }





}
