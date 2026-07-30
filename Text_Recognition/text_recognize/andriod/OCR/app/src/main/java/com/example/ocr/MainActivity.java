package com.example.ocr;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final int CAMERA_REQUEST = 100;
    private static final int PERMISSION_REQUEST_CODE = 300;

    private TextView statusText;
    private TextView resultText;
    private Button btnCapture;
    private Button btnReplay;
    private Button btnStop;

    private TextToSpeech textToSpeech;
    private boolean isTtsReady = false;
    private String lastRecognizedText = "";

    private String lastFocusedButtonName = "";
    private boolean isReadingOcrContent = false;
    private int pendingSpeakCount = 0;

    private static final String OCR_URL =
            "http://172.20.10.5:8000/ocr/doc";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        resultText = findViewById(R.id.resultText);
        btnCapture = findViewById(R.id.btnCapture);
        btnReplay = findViewById(R.id.btnReplay);
        btnStop = findViewById(R.id.btnStop);

        textToSpeech = new TextToSpeech(this, this);

        btnCapture.setOnClickListener(v -> {
            speakSystemText("開啟相機");
            statusText.postDelayed(this::openCamera, 350);
        });

        btnReplay.setOnClickListener(v -> replayLastText());

        btnStop.setOnClickListener(v -> {
            stopSpeaking();
            speakSystemText("已停止朗讀");
        });

        setFocusEffect(btnCapture);
        setFocusEffect(btnReplay);
        setFocusEffect(btnStop);

        btnCapture.requestFocus();

        updateStatus("準備完成");
        checkPermissions();
    }

    private void setFocusEffect(Button button) {
        button.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate()
                        .scaleX(1.15f)
                        .scaleY(1.15f)
                        .alpha(1.0f)
                        .setDuration(150)
                        .start();
                v.setElevation(16f);

                String buttonName = button.getText().toString().trim();
                if (!buttonName.equals(lastFocusedButtonName)) {
                    lastFocusedButtonName = buttonName;
                    speakSystemText(buttonName);
                }

            } else {
                v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .alpha(0.85f)
                        .setDuration(150)
                        .start();
                v.setElevation(2f);
            }
        });

        button.setAlpha(0.85f);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = textToSpeech.setLanguage(Locale.TAIWAN);

            if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = false;
                updateStatus("語音功能不支援繁體中文");
            } else {
                isTtsReady = true;
                textToSpeech.setSpeechRate(0.85f);
                textToSpeech.setPitch(0.95f);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    textToSpeech.setAudioAttributes(
                            new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                    );
                }

                textToSpeech.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        pendingSpeakCount--;
                        if (pendingSpeakCount <= 0) {
                            pendingSpeakCount = 0;
                            if (isReadingOcrContent) {
                                isReadingOcrContent = false;
                            }
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        pendingSpeakCount--;
                        if (pendingSpeakCount <= 0) {
                            pendingSpeakCount = 0;
                            isReadingOcrContent = false;
                        }
                    }
                });

                speakSystemText("請滑動選擇按鈕，再點一下執行");
            }
        } else {
            isTtsReady = false;
            updateStatus("語音功能初始化失敗");
        }
    }

    private void updateStatus(String message) {
        runOnUiThread(() -> statusText.setText("狀態：" + message));
    }

    private void checkPermissions() {
        List<String> permissions = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }

        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    permissions.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE
            );
        }
    }

    private void openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            checkPermissions();
            speakSystemText("尚未取得相機權限");
            return;
        }

        Intent intent = new Intent(this, CameraPreviewActivity.class);
        startActivityForResult(intent, CAMERA_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        android.util.Log.d("OCR_TEST", "onActivityResult 被呼叫 requestCode=" + requestCode + ", resultCode=" + resultCode);

        speakSystemText("已回到主程式");

        if (resultCode != Activity.RESULT_OK) {
            updateStatus("操作已取消");
            speakSystemText("操作已取消");
            return;
        }

        if (requestCode == CAMERA_REQUEST) {
            if (data == null || data.getByteArrayExtra("imageBytes") == null) {
                updateStatus("拍照失敗，沒有取得圖片");
                speakSystemText("拍照失敗");
                return;
            }

            try {
                byte[] imageBytes = data.getByteArrayExtra("imageBytes");

                Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(
                        imageBytes,
                        0,
                        imageBytes.length
                );

                if (bitmap == null) {
                    updateStatus("照片解析失敗");
                    speakSystemText("照片解析失敗");
                    return;
                }

                startRecognition(bitmap, "拍照完成，開始辨識");

            } catch (Exception e) {
                updateStatus("讀取拍照圖片失敗");
                resultText.setText("讀取照片失敗：" + e.getMessage());
                speakSystemText("讀取照片失敗");
            }
        }
    }

    private void startRecognition(Bitmap bitmap, String statusMessage) {
        updateStatus(statusMessage);
        resultText.setText("辨識中，請稍候...");
        speakSystemText("正在辨識");
        uploadImage(bitmap);
    }

    private void uploadImage(Bitmap bitmap) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream);
        byte[] byteArray = stream.toByteArray();

        OkHttpClient client = new OkHttpClient();

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                        "file",
                        "image.jpg",
                        RequestBody.create(MediaType.parse("image/*"), byteArray)
                )
                .build();

        Request request = new Request.Builder()
                .url(OCR_URL)
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                updateStatus("連線失敗");
                runOnUiThread(() -> {
                    resultText.setText("錯誤：" + e.getMessage());
                    speakSystemText("連線失敗，請檢查伺服器或網路");
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String result = response.body() != null ? response.body().string() : "";

                try {
                    JSONObject json = new JSONObject(result);
                    String text = json.optString("text", "").trim();

                    runOnUiThread(() -> {
                        if (text.isEmpty()) {
                            lastRecognizedText = "";
                            updateStatus("沒有辨識到文字");
                            resultText.setText("沒有辨識到文字");
                            speakSystemText("沒有辨識到文字");
                        } else {
                            lastRecognizedText = text;
                            updateStatus("辨識完成");
                            resultText.setText(text);
                            speakOcrContent("辨識完成，內容如下。\n" + text);
                        }
                    });

                } catch (Exception e) {
                    updateStatus("資料解析失敗");
                    runOnUiThread(() -> {
                        resultText.setText("解析錯誤：" + result);
                        speakSystemText("資料解析錯誤");
                    });
                }
            }
        });
    }

    private void replayLastText() {
        if (lastRecognizedText == null || lastRecognizedText.trim().isEmpty()) {
            speakSystemText("目前沒有可以重新朗讀的辨識內容");
        } else {
            updateStatus("重新朗讀中");
            speakOcrContent("重新朗讀，內容如下。\n" + lastRecognizedText);
        }
    }

    private void stopSpeaking() {
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
        pendingSpeakCount = 0;
        isReadingOcrContent = false;
        updateStatus("已停止朗讀");
    }

    private void speakSystemText(String text) {
        if (textToSpeech != null && isTtsReady) {
            pendingSpeakCount = 1;
            isReadingOcrContent = false;

            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, "OCR_SYSTEM_TTS");
        }
    }

    private void speakOcrContent(String text) {
        if (textToSpeech == null || !isTtsReady) {
            return;
        }

        textToSpeech.stop();

        List<String> segments = splitTextForSpeech(text);
        if (segments.isEmpty()) {
            return;
        }

        pendingSpeakCount = segments.size();
        isReadingOcrContent = true;

        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);

        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i).trim();
            if (!segment.isEmpty()) {
                String utteranceId = "OCR_TTS_" + i;
                if (i == 0) {
                    textToSpeech.speak(segment, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
                } else {
                    textToSpeech.speak(segment, TextToSpeech.QUEUE_ADD, params, utteranceId);
                }
            }
        }
    }

    private List<String> splitTextForSpeech(String text) {
        List<String> result = new ArrayList<>();

        if (text == null) {
            return result;
        }

        text = text.trim();
        if (text.isEmpty()) {
            return result;
        }

        text = text.replace("\r\n", "\n").replace("\r", "\n");
        text = text.replaceAll("[ \t]+", " ");

        String[] paragraphs = text.split("\\n+");

        for (String paragraph : paragraphs) {
            paragraph = paragraph.trim();
            if (paragraph.isEmpty()) {
                continue;
            }

            paragraph = paragraph.replace("。", "。 ");
            paragraph = paragraph.replace("，", "， ");
            paragraph = paragraph.replace("！", "！ ");
            paragraph = paragraph.replace("？", "？ ");
            paragraph = paragraph.replace("：", "： ");
            paragraph = paragraph.replace("；", "； ");

            if (paragraph.length() <= 20 && !paragraph.endsWith("。")) {
                paragraph = "標題，" + paragraph + "。";
            }

            String[] sentences = paragraph.split("(?<=[。！？；])");

            StringBuilder chunk = new StringBuilder();

            for (String sentence : sentences) {
                sentence = sentence.trim();
                if (sentence.isEmpty()) {
                    continue;
                }

                if (chunk.length() + sentence.length() > 80) {
                    if (chunk.length() > 0) {
                        result.add(chunk.toString().trim());
                        chunk.setLength(0);
                    }
                }

                chunk.append(sentence).append(" ");
            }

            if (chunk.length() > 0) {
                result.add(chunk.toString().trim());
            }
        }

        return result;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;

            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                updateStatus("權限已取得");
            } else {
                updateStatus("請允許相機權限");
                speakSystemText("請允許相機權限");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        statusText.postDelayed(() -> {
            if (!btnCapture.hasFocus()
                    && !btnReplay.hasFocus()
                    && !btnStop.hasFocus()) {
                btnCapture.requestFocus();
            }
        }, 300);
    }

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }

        super.onDestroy();
    }
}