package com.example.ocr;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class CameraPreviewActivity extends Activity {

    private TextureView textureView;
    private Button btnTakePhoto;
    private TextView txtCameraStatus;

    private CameraDevice cameraDevice;
    private CameraCaptureSession previewSession;
    private ImageReader imageReader;
    private String cameraId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_preview);

        textureView = findViewById(R.id.textureView);
        btnTakePhoto = findViewById(R.id.btnTakePhoto);
        txtCameraStatus = findViewById(R.id.txtCameraStatus);

        btnTakePhoto.setEnabled(false);
        btnTakePhoto.setOnClickListener(v -> takePhoto());

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                closeCamera();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        try {
            txtCameraStatus.setText("相機啟動中...");

            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            cameraId = manager.getCameraIdList()[0];

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    startPreview();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    finish();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    txtCameraStatus.setText("相機開啟失敗");
                    finish();
                }
            }, null);

        } catch (Exception e) {
            txtCameraStatus.setText("相機啟動失敗：" + e.getMessage());
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private void startPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();

            if (texture == null || cameraDevice == null) {
                txtCameraStatus.setText("相機預覽失敗");
                return;
            }

            texture.setDefaultBufferSize(1280, 720);
            Surface previewSurface = new Surface(texture);

            imageReader = ImageReader.newInstance(
                    1280,
                    720,
                    ImageFormat.JPEG,
                    1
            );

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;

                try {
                    image = reader.acquireLatestImage();

                    if (image == null) {
                        setResult(RESULT_CANCELED);
                        finish();
                        return;
                    }

                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);

                    Intent data = new Intent();
                    data.putExtra("imageBytes", bytes);

                    setResult(RESULT_OK, data);
                    finish();

                } catch (Exception e) {
                    setResult(RESULT_CANCELED);
                    finish();

                } finally {
                    if (image != null) {
                        image.close();
                    }
                    closeCamera();
                }
            }, null);

            CaptureRequest.Builder previewBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            previewBuilder.addTarget(previewSurface);

            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                previewSession = session;
                                session.setRepeatingRequest(
                                        previewBuilder.build(),
                                        null,
                                        null
                                );

                                txtCameraStatus.setText("請對準文字後按拍照");
                                btnTakePhoto.setEnabled(true);
                                btnTakePhoto.requestFocus();

                            } catch (Exception e) {
                                txtCameraStatus.setText("預覽啟動失敗：" + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            txtCameraStatus.setText("相機設定失敗");
                        }
                    },
                    null
            );

        } catch (Exception e) {
            txtCameraStatus.setText("預覽失敗：" + e.getMessage());
        }
    }

    private void takePhoto() {
        if (cameraDevice == null || imageReader == null || previewSession == null) {
            txtCameraStatus.setText("相機尚未準備完成");
            return;
        }

        try {
            btnTakePhoto.setEnabled(false);
            txtCameraStatus.setText("拍照中...");

            CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            captureBuilder.addTarget(imageReader.getSurface());

            previewSession.capture(
                    captureBuilder.build(),
                    null,
                    null
            );

        } catch (Exception e) {
            txtCameraStatus.setText("拍照失敗：" + e.getMessage());
            btnTakePhoto.setEnabled(true);
        }
    }

    private void closeCamera() {
        try {
            if (previewSession != null) {
                previewSession.close();
                previewSession = null;
            }

            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }

            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }

        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        closeCamera();
        super.onDestroy();
    }
}
