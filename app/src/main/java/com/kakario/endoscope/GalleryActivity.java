package com.kakario.endoscope;

import android.Manifest;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GalleryActivity extends AppCompatActivity {

    private static final int REQ_READ = 20;
    private static final int REQ_DELETE = 21;

    static class Item {
        Uri uri;
        boolean video;
        long date;
        long durationMs;
    }

    private final List<Item> items = new ArrayList<>();
    private final LruCache<Uri, Bitmap> cache = new LruCache<>(120);
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private GridView grid;
    private TextView empty;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("찍은 사진·영상");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        int p = dp(16);
        title.setPadding(p, p, p, dp(8));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("누르면 보기 · 길게 누르면 삭제");
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(13);
        hint.setPadding(p, 0, p, dp(8));
        root.addView(hint);

        FrameLayout body = new FrameLayout(this);
        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(110));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(2));
        grid.setVerticalSpacing(dp(2));
        body.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        empty = new TextView(this);
        empty.setText("아직 저장된 사진·영상이 없어요");
        empty.setTextColor(0x99FFFFFF);
        empty.setGravity(Gravity.CENTER);
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> {
            Item it = items.get(pos);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(it.uri, it.video ? "video/*" : "image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "열 수 있는 앱이 없어요", Toast.LENGTH_SHORT).show();
            }
        });
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            deleteItem(items.get(pos));
            return true;
        });

        String[] perms = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO}
                : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        if (checkSelfPermission(perms[0]) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(perms, REQ_READ);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        load();
    }

    private void load() {
        items.clear();
        query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), false);
        query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true);
        Collections.sort(items, (a, b) -> Long.compare(b.date, a.date));
        adapter.notifyDataSetChanged();
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void query(Uri collection, boolean video) {
        String[] proj = video
                ? new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED, MediaStore.Video.Media.DURATION}
                : new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED};
        String sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        String[] args = {"%/" + MainActivity.FOLDER + "/%"};
        try (Cursor c = getContentResolver().query(collection, proj, sel, args, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                Item it = new Item();
                it.uri = ContentUris.withAppendedId(collection, c.getLong(0));
                it.date = c.getLong(1);
                it.video = video;
                if (video) it.durationMs = c.getLong(2);
                items.add(it);
            }
        } catch (Exception ignored) {
        }
    }

    private void deleteItem(Item it) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), Collections.singletonList(it.uri));
                startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
            } catch (Exception e) {
                Toast.makeText(this, "삭제 실패", Toast.LENGTH_SHORT).show();
            }
        } else {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setMessage("삭제할까요?")
                    .setPositiveButton("삭제", (d, w) -> {
                        try {
                            getContentResolver().delete(it.uri, null, null);
                        } catch (Exception ignored) {
                        }
                        load();
                    })
                    .setNegativeButton("취소", null)
                    .show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DELETE) load();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            FrameLayout cell;
            ImageView img;
            TextView badge;
            if (convert == null) {
                cell = new FrameLayout(GalleryActivity.this);
                int w = grid.getColumnWidth() > 0 ? grid.getColumnWidth() : dp(110);
                cell.setLayoutParams(new GridView.LayoutParams(-1, w));
                img = new ImageView(GalleryActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackgroundColor(0xFF1A1A1A);
                cell.addView(img, new FrameLayout.LayoutParams(-1, -1));
                badge = new TextView(GalleryActivity.this);
                badge.setTextColor(Color.WHITE);
                badge.setTextSize(12);
                badge.setShadowLayer(4, 0, 0, Color.BLACK);
                badge.setPadding(dp(6), dp(4), dp(6), dp(4));
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
                cell.addView(badge, blp);
                cell.setTag(new Object[]{img, badge});
            } else {
                cell = (FrameLayout) convert;
            }
            Object[] tag = (Object[]) cell.getTag();
            img = (ImageView) tag[0];
            badge = (TextView) tag[1];

            Item it = items.get(pos);
            if (it.video) {
                long s = it.durationMs / 1000;
                badge.setText(String.format(Locale.US, "▶ %d:%02d", s / 60, s % 60));
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }
            img.setTag(it.uri);
            Bitmap b = cache.get(it.uri);
            if (b != null) {
                img.setImageBitmap(b);
            } else {
                img.setImageDrawable(null);
                final ImageView target = img;
                pool.execute(() -> {
                    try {
                        Bitmap bm = getContentResolver().loadThumbnail(it.uri, new Size(320, 320), null);
                        cache.put(it.uri, bm);
                        runOnUiThread(() -> {
                            if (it.uri.equals(target.getTag())) target.setImageBitmap(bm);
                        });
                    } catch (Exception ignored) {
                    }
                });
            }
            return cell;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}
