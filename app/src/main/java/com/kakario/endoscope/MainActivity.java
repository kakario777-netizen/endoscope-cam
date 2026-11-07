package com.kakario.endoscope;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.herohan.uvcapp.ImageCapture;
import com.herohan.uvcapp.VideoCapture;
import com.serenegiant.usb.Size;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    static final String FOLDER = "Endoscope";
    private static final int REQ_PERM = 10;
    private static final float[] ZOOM_STEPS = {1f, 1.5f, 2f, 3f};

    private ICameraHelper camera;
    private TextureView preview;
    private FrameLayout stage;
    private TextView status, recTime, btnZoom, btnRec;
    private Surface previewSurface;
    private boolean cameraOpen = false;
    private boolean surfaceAdded = false;
    private boolean recording = false;
    private long recStart;
    private File recFile;

    private int camW = 640, camH = 480;
    private int rotation = 0;          // 0, 90, 180, 270
    private boolean mirror = false;
    private float zoom = 1f;
    private float panX = 0f, panY = 0f;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        stage = findViewById(R.id.stage);
        preview = findViewById(R.id.preview);
        status = findViewById(R.id.status);
        recTime = findViewById(R.id.recTime);
        btnZoom = findViewById(R.id.btnZoom);
        btnRec = findViewById(R.id.btnRec);
        ImageButton btnShot = findViewById(R.id.btnShot);

        preview.setSurfaceTextureListener(textureListener);
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyTransform());

        btnShot.setOnClickListener(v -> takePhoto());
        btnRec.setOnClickListener(v -> toggleRecord());
        findViewById(R.id.btnGallery).setOnClickListener(v ->
                startActivity(new Intent(this, GalleryActivity.class)));
        findViewById(R.id.btnRotate).setOnClickListener(v -> {
            rotation = (rotation + 90) % 360;
            panX = panY = 0;
            applyTransform();
        });
        findViewById(R.id.btnMirror).setOnClickListener(v -> {
            mirror = !mirror;
            applyTransform();
        });
        btnZoom.setOnClickListener(v -> {
            int next = 0;
            for (int i = 0; i < ZOOM_STEPS.length; i++) {
                if (zoom < ZOOM_STEPS[i] - 0.01f) { next = i; break; }
            }
            setZoom(ZOOM_STEPS[next]);
        });
        setupGestures();
        ensurePermissions();
    }

    // ---------- 권한 ----------
    private void ensurePermissions() {
        String[] perms = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        boolean ok = true;
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ok = false;
        }
        if (ok) initCamera();
        else requestPermissions(perms, REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                initCamera();
            } else {
                status.setText("카메라 권한이 있어야 내시경 화면을 볼 수 있어요");
            }
        }
    }

    // ---------- 카메라 ----------
    private void initCamera() {
        if (camera != null) return;
        camera = new CameraHelper();
        camera.setStateCallback(stateCallback);
    }

    private final ICameraHelper.StateCallback stateCallback = new ICameraHelper.StateCallback() {
        @Override
        public void onAttach(UsbDevice device) {
            status.setText("내시경 연결 중...");
            camera.selectDevice(device);
        }

        @Override
        public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
            camera.openCamera();
        }

        @Override
        public void onCameraOpen(UsbDevice device) {
            camera.startPreview();
            Size size = camera.getPreviewSize();
            if (size != null) {
                camW = size.width;
                camH = size.height;
            }
            cameraOpen = true;
            status.setVisibility(View.GONE);
            attachSurface();
            applyTransform();
        }

        @Override
        public void onCameraClose(UsbDevice device) {
            if (recording) stopRecordUi();
            detachSurface();
            cameraOpen = false;
        }

        @Override
        public void onDeviceClose(UsbDevice device) {
        }

        @Override
        public void onDetach(UsbDevice device) {
            cameraOpen = false;
            status.setText("내시경이 분리됐어요\n다시 꽂아 주세요");
            status.setVisibility(View.VISIBLE);
        }

        @Override
        public void onCancel(UsbDevice device) {
            status.setText("USB 접근이 허용되지 않았어요\n다시 꽂고 '허용'을 눌러 주세요");
            status.setVisibility(View.VISIBLE);
        }
    };

    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
            previewSurface = new Surface(st);
            attachSurface();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
            detachSurface();
            if (previewSurface != null) previewSurface.release();
            previewSurface = null;
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {
        }
    };

    private void attachSurface() {
        if (camera != null && cameraOpen && previewSurface != null && !surfaceAdded) {
            camera.addSurface(previewSurface, false);
            surfaceAdded = true;
        }
    }

    private void detachSurface() {
        if (camera != null && previewSurface != null && surfaceAdded) {
            try {
                camera.removeSurface(previewSurface);
            } catch (Exception ignored) {
            }
        }
        surfaceAdded = false;
    }

    // ---------- 화면 맞춤·회전·반전·확대 ----------
    private void applyTransform() {
        int W = stage.getWidth(), H = stage.getHeight();
        if (W == 0 || H == 0 || camW == 0 || camH == 0) return;
        boolean sideways = rotation % 180 != 0;
        float visW = sideways ? camH : camW;
        float visH = sideways ? camW : camH;
        float s = Math.min(W / visW, H / visH);
        int lw = Math.round(camW * s), lh = Math.round(camH * s);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) preview.getLayoutParams();
        if (lp.width != lw || lp.height != lh) {
            lp.width = lw;
            lp.height = lh;
            lp.gravity = android.view.Gravity.CENTER;
            preview.setLayoutParams(lp);
        }
        preview.setRotation(rotation);
        preview.setScaleX(zoom * (mirror ? -1 : 1));
        preview.setScaleY(zoom);
        float maxX = Math.max(0, (visW * s * zoom - W) / 2f);
        float maxY = Math.max(0, (visH * s * zoom - H) / 2f);
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
        preview.setTranslationX(panX);
        preview.setTranslationY(panY);
        btnZoom.setText(String.format(Locale.US, "%.1fx", zoom));
    }

    private void setZoom(float z) {
        zoom = Math.max(1f, Math.min(4f, z));
        if (zoom <= 1.001f) panX = panY = 0;
        applyTransform();
    }

    private void setupGestures() {
        ScaleGestureDetector scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(@NonNull ScaleGestureDetector d) {
                        setZoom(zoom * d.getScaleFactor());
                        return true;
                    }
                });
        GestureDetector gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float dx, float dy) {
                        if (zoom > 1.001f) {
                            panX -= dx;
                            panY -= dy;
                            applyTransform();
                        }
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(@NonNull MotionEvent e) {
                        setZoom(zoom > 1.001f ? 1f : 2f);
                        return true;
                    }
                });
        stage.setOnTouchListener((v, ev) -> {
            scaleDetector.onTouchEvent(ev);
            if (!scaleDetector.isInProgress()) gestureDetector.onTouchEvent(ev);
            return true;
        });
    }

    // ---------- 사진 ----------
    private String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(new Date());
    }

    private void takePhoto() {
        if (camera == null || !cameraOpen) {
            toast("내시경이 연결되지 않았어요");
            return;
        }
        File f = new File(getCacheDir(), "IMG_" + stamp() + ".jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(f).build();
        camera.takePicture(options, new ImageCapture.OnImageCaptureCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                Uri uri = copyToGallery(f, "image/jpeg", true);
                runOnUiThread(() -> {
                    flash();
                    toast(uri != null ? "사진 저장됨" : "사진 저장 실패");
                });
            }

            @Override
            public void onError(int code, @NonNull String message, @Nullable Throwable cause) {
                runOnUiThread(() -> toast("사진 촬영 실패: " + message));
            }
        });
    }

    private void flash() {
        View root = findViewById(android.R.id.content);
        root.setAlpha(0.3f);
        root.animate().alpha(1f).setDuration(200).start();
    }

    // ---------- 녹화 ----------
    private void toggleRecord() {
        if (camera == null || !cameraOpen) {
            toast("내시경이 연결되지 않았어요");
            return;
        }
        if (recording) {
            camera.stopRecording();
            stopRecordUi();
            return;
        }
        recFile = new File(getCacheDir(), "VID_" + stamp() + ".mp4");
        final File target = recFile;
        VideoCapture.OutputFileOptions options = new VideoCapture.OutputFileOptions.Builder(target).build();
        camera.startRecording(options, new VideoCapture.OnVideoCaptureCallback() {
            @Override
            public void onStart() {
            }

            @Override
            public void onVideoSaved(@NonNull VideoCapture.OutputFileResults results) {
                Uri uri = copyToGallery(target, "video/mp4", false);
                runOnUiThread(() -> toast(uri != null ? "영상 저장됨" : "영상 저장 실패"));
            }

            @Override
            public void onError(int code, @NonNull String message, @Nullable Throwable cause) {
                runOnUiThread(() -> {
                    stopRecordUi();
                    toast("녹화 실패: " + message);
                });
            }
        });
        recording = true;
        recStart = System.currentTimeMillis();
        btnRec.setText("정지");
        btnRec.setBackgroundResource(R.drawable.btn_rec_on);
        handler.post(recTicker);
    }

    private void stopRecordUi() {
        recording = false;
        handler.removeCallbacks(recTicker);
        recTime.setText("");
        btnRec.setText("녹화");
        btnRec.setBackgroundResource(R.drawable.btn_round);
    }

    private final Runnable recTicker = new Runnable() {
        @Override
        public void run() {
            if (!recording) return;
            long sec = (System.currentTimeMillis() - recStart) / 1000;
            recTime.setText(String.format(Locale.US, "● REC %02d:%02d", sec / 60, sec % 60));
            handler.postDelayed(this, 500);
        }
    };

    // ---------- 갤러리 저장 ----------
    private Uri copyToGallery(File src, String mime, boolean image) {
        ContentResolver cr = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                (image ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES) + "/" + FOLDER);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = image
                ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = null;
        try {
            uri = cr.insert(collection, cv);
            if (uri == null) return null;
            try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(uri)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            //noinspection ResultOfMethodCallIgnored
            src.delete();
            return uri;
        } catch (Exception e) {
            if (uri != null) cr.delete(uri, null, null);
            return null;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (camera != null) {
            if (recording) camera.stopRecording();
            camera.release();
            camera = null;
        }
    }
}
