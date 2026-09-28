package com.cinematic.crop;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 单页批量裁剪工具,操作逻辑与网页版 index.html 一致:
 * 编辑画布 + 底部照片条(点击切换/勾选) + 五图标工具栏 + 重置/确认栏。
 * 确认 = 勾选当前并跳到下一张未勾选;取消 = 清空全部;
 * 导出勾选的图片,未勾选任何时导出当前这张。
 * 导出仍走原生管线(BitmapRegionDecoder 按原图区域解码 + MediaStore 写下载目录)。
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int REQ_PERM = 2;
    private static final int GALLERY_MAX = 300;        // 相册选择器加载的最近照片数
    private static final double CINEMATIC = 2.7087;   // 内容区宽:高(横版)
    private static final float BAR_PCT_DEFAULT = 4.5385f; // 默认黑边厚度(占内容长边 %)
    private static final int EDIT_MAX = 2048;          // 编辑位图长边上限(仅影响编辑清晰度,导出仍按原图区域解码)
    private static final int OUT_MAX_DEFAULT = 4096;   // 未指定输出尺寸时的长边上限(防爆内存)
    private static final int THUMB_MAX = 256;          // 缩略图长边

    private static final int ACCENT = 0xFF66D9C0;      // 强调薄荷绿
    private static final int ACCENT_TEXT = 0xFF0D2B24; // 强调色上的文字
    private static final int CIRCLE_BG = 0xFF2C2C2E;   // 未选中 chip 底色
    private static final int CHECK_BLUE = 0xFF4A7DFF;  // 勾选蓝

    /** 内置比例定义;key = "orig"/"free" 或数值字符串,value 为横版宽:高 */
    private static final class RatioDef {
        final String key, label, sub;
        final double value;
        final boolean custom;
        RatioDef(String key, String label, String sub, double value) {
            this(key, label, sub, value, false);
        }
        RatioDef(String key, String label, String sub, double value, boolean custom) {
            this.key = key; this.label = label; this.sub = sub; this.value = value; this.custom = custom;
        }
    }

    private static final RatioDef[] FIXED_RATIOS = {
            new RatioDef("orig", "原始", null, 0),
            new RatioDef("cinematic", "电影画幅", "2.71:1", CINEMATIC),
            new RatioDef("2.39", "2.39:1", "宽银幕", 2.39),
            new RatioDef("16:9", "16:9", null, 1.7778),
            new RatioDef("1:1", "1:1", "正方形", 1.0),
            new RatioDef("4:3", "4:3", null, 1.3333),
            new RatioDef("3:4", "3:4", null, 0.75),
            new RatioDef("9:16", "9:16", "IG 快拍", 0.5625),
            new RatioDef("free", "自由", null, 0),
    };
    private static final int RATIO_DEFAULT = 1; // 电影画幅

    /** 内置 + 自定义比例,由 buildRatioChips 重建 */
    private final List<RatioDef> ratios = new ArrayList<>();

    private static final int[] RES_VALS = {0, 3840, 2560, 1920};

    /** 一张已选图片的状态 */
    private static class ImgItem {
        Uri uri;
        String name;
        int origW, origH;        // 原图(未旋转)尺寸
        Bitmap thumb;            // 随 rotation 一起旋转,始终与编辑画布同方向
        RectF cropNorm;          // 归一化裁剪框(0~1,旋转后坐标系),null = 导出时按当前比例取默认居中框
        boolean checked = false; // 与网页版一致:默认不勾选,「确认」或点勾选钮后勾选
        int rotation = 0;        // 0/90/180/270,顺时针
        boolean landscape = true;// 每张图自己的横竖方向
    }

    // 顶栏
    private Button btnCancel, btnExport;
    // 预览区
    private View emptyState;
    private CropImageView cropView;
    private TextView pageInfo;
    // 工具栏 + 选项面板
    private ImageButton[] toolBtns;
    private View options, paneRatio, paneBars, paneRotate, paneOrient, paneOutput;
    private LinearLayout ratioChips, qualityRow;
    private CheckBox barsCheck;
    private SeekBar barsSeek, qualitySeek;
    private TextView barsVal, qualityVal;
    private Button btnBarsReset;
    private Button btnRotL, btnRotR, btnLandscape, btnPortrait;
    private Button[] resBtns, fmtBtns;
    private EditText outWidth;
    // 重置 / 应用到全部
    private View confirmBar;
    private Button btnReset, btnApplyAll;
    // 底部照片条
    private TextView pickerHead, addThumb;
    private Button btnSelectAll;
    private HorizontalScrollView pickerScroll;
    private LinearLayout pickerRow;

    private final List<ImgItem> items = new ArrayList<>();
    private int currentIndex = -1;         // 正在编辑的图片下标
    private int loadSeq = 0;               // 异步解码防乱序

    private Bitmap editBitmap;             // 当前编辑位图(已按 rotation 旋转)
    private int ratioIndex = RATIO_DEFAULT;
    private int outFormat = 1;             // 0=PNG 1=JPG 2=WebP,默认 JPG

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService decodePool = Executors.newFixedThreadPool(3); // 缩略图并行解码

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnCancel = findViewById(R.id.btnCancel);
        btnExport = findViewById(R.id.btnExport);
        emptyState = findViewById(R.id.emptyState);
        cropView = findViewById(R.id.cropView);
        pageInfo = findViewById(R.id.pageInfo);

        options = findViewById(R.id.options);
        paneRatio = findViewById(R.id.paneRatio);
        paneBars = findViewById(R.id.paneBars);
        paneRotate = findViewById(R.id.paneRotate);
        paneOrient = findViewById(R.id.paneOrient);
        paneOutput = findViewById(R.id.paneOutput);
        ratioChips = findViewById(R.id.ratioChips);
        barsCheck = findViewById(R.id.barsCheck);
        barsSeek = findViewById(R.id.barsSeek);
        barsVal = findViewById(R.id.barsVal);
        btnBarsReset = findViewById(R.id.barsReset);
        btnRotL = findViewById(R.id.btnRotL);
        btnRotR = findViewById(R.id.btnRotR);
        btnLandscape = findViewById(R.id.btnLandscape);
        btnPortrait = findViewById(R.id.btnPortrait);
        outWidth = findViewById(R.id.outWidth);
        qualityRow = findViewById(R.id.qualityRow);
        qualitySeek = findViewById(R.id.qualitySeek);
        qualityVal = findViewById(R.id.qualityVal);
        resBtns = new Button[]{findViewById(R.id.btnResOrig), findViewById(R.id.btnRes4k),
                findViewById(R.id.btnRes2k), findViewById(R.id.btnRes1080)};
        fmtBtns = new Button[]{findViewById(R.id.btnFmtPng), findViewById(R.id.btnFmtJpg),
                findViewById(R.id.btnFmtWebp)};

        confirmBar = findViewById(R.id.confirmBar);
        btnReset = findViewById(R.id.btnReset);
        btnApplyAll = findViewById(R.id.btnApplyAll);

        pickerHead = findViewById(R.id.pickerHead);
        pickerScroll = findViewById(R.id.pickerScroll);
        pickerRow = findViewById(R.id.pickerRow);
        addThumb = findViewById(R.id.addThumb);
        btnSelectAll = findViewById(R.id.btnSelectAll);

        // 顶栏
        btnCancel.setOnClickListener(v -> confirmClearAll());
        btnExport.setOnClickListener(v -> {
            if (items.isEmpty()) return;
            showExportDialog(this::exportSelected);
        });

        // 空状态 / 加图入口
        emptyState.setOnClickListener(v -> pickImages());
        addThumb.setOnClickListener(v -> pickImages());
        pickerHead.setOnClickListener(v -> pickImages());
        btnSelectAll.setOnClickListener(v -> toggleAllPhotos());

        // 工具栏:切换选项面板,与网页版 toolOrder 一致;选中态 = 黄色图标 + 深灰底
        toolBtns = new ImageButton[]{findViewById(R.id.toolRatio), findViewById(R.id.toolBars),
                findViewById(R.id.toolRotate), findViewById(R.id.toolOrient), findViewById(R.id.toolOutput)};
        final View[] panes = {paneRatio, paneBars, paneRotate, paneOrient, paneOutput};
        for (int i = 0; i < toolBtns.length; i++) {
            final int idx = i;
            toolBtns[i].setOnClickListener(v -> {
                for (int j = 0; j < toolBtns.length; j++) {
                    boolean active = j == idx;
                    toolBtns[j].setColorFilter(active ? ACCENT : 0xFFBBBBBB);
                    toolBtns[j].setBackgroundColor(active ? CIRCLE_BG : Color.TRANSPARENT);
                    panes[j].setVisibility(active ? View.VISIBLE : View.GONE);
                }
            });
        }
        toolBtns[0].performClick(); // 默认选中「比例」

        buildRatioChips();

        // 黑边(全局设置,与网页版一致默认开);拖动滑杆/开关时画布实时预览
        barsVal.setText(String.format(Locale.US, "%.2f%%", BAR_PCT_DEFAULT));
        barsCheck.setOnCheckedChangeListener((b, c) -> updateBarsPreview());
        barsSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                barsVal.setText(String.format(Locale.US, "%.2f%%", progress / 100f));
                updateBarsPreview();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        // 黑边厚度重置为默认值(4.54%)
        btnBarsReset.setOnClickListener(v ->
                barsSeek.setProgress(Math.round(BAR_PCT_DEFAULT * 100))); // setProgress 会触发滑杆回调刷新预览

        // 旋转 / 横竖方向
        btnRotL.setOnClickListener(v -> rotateCurrent(-90));
        btnRotR.setOnClickListener(v -> rotateCurrent(90));
        btnLandscape.setOnClickListener(v -> setOrientation(true));
        btnPortrait.setOnClickListener(v -> setOrientation(false));

        // 输出尺寸:分辨率预设即填入对应长边像素;手动输入时按数值匹配预设选中态(网页版 syncResChips)
        for (int i = 0; i < resBtns.length; i++) {
            final int idx = i;
            resBtns[i].setOnClickListener(v -> outWidth.setText(String.valueOf(RES_VALS[idx])));
        }
        outWidth.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { syncResChips(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        syncResChips();

        // 格式:PNG 无损时隐藏质量滑杆
        for (int i = 0; i < fmtBtns.length; i++) {
            final int idx = i;
            fmtBtns[i].setOnClickListener(v -> setOutFormat(idx));
        }
        setOutFormat(1); // 默认 JPG,质量滑杆默认显示
        qualitySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                qualityVal.setText((progress + 50) + "%");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        // 重置 / 应用到全部
        btnReset.setOnClickListener(v -> {
            if (editBitmap == null) return;
            cropView.resetCrop();
            saveCurrentCrop();
            rebuildPicker();
        });
        btnApplyAll.setOnClickListener(v -> applyCropToAll());

        // 裁剪框手势结束:存回状态并同步缩略图上的裁剪范围(网页版 render → drawThumb)
        cropView.setOnCropChangeListener(() -> { saveCurrentCrop(); rebuildPicker(); });
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------- 选图 / 载入 ----------

    /** 有相册权限 → 应用内相册选择器;没有 → 先申请;被拒绝过 → 退回系统选择器 */
    private void pickImages() {
        if (hasMediaPermission()) showGalleryPicker();
        else requestMediaPermission();
    }

    private boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= 34
                && checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) return true; // 部分照片访问
        if (Build.VERSION.SDK_INT >= 33)
            return checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void requestMediaPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}, REQ_PERM);
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{android.Manifest.permission.READ_MEDIA_IMAGES}, REQ_PERM);
        } else {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERM) return;
        if (hasMediaPermission()) showGalleryPicker();
        else {
            Toast.makeText(this, "未授权读取相册,改用系统选择器", Toast.LENGTH_SHORT).show();
            pickImagesViaSystem();
        }
    }

    /** 系统文档选择器(无需权限,作为拒绝授权时的兜底) */
    private void pickImagesViaSystem() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(i, "选择图片(可多选)"), REQ_PICK);
    }

    // ---------- 应用内相册选择器 ----------

    /** 查询相册最近的照片(需已授权),按添加时间倒序 */
    private List<Uri> queryRecentImages() {
        List<Uri> out = new ArrayList<>();
        String[] proj = {MediaStore.Images.Media._ID};
        String sort = MediaStore.Images.Media.DATE_ADDED + " DESC";
        try (Cursor c = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, sort)) {
            if (c == null) return out;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            while (c.moveToNext() && out.size() < GALLERY_MAX) {
                out.add(ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** 相册缩略图:API 29+ 走 loadThumbnail,更早的版本按采样率解码 */
    private Bitmap loadGalleryThumb(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return getContentResolver().loadThumbnail(
                        uri, new android.util.Size(THUMB_MAX, THUMB_MAX), null);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 4;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 全屏相册选择器:直接读相册最近照片,点选多张后「添加」导入(可多批追加) */
    private void showGalleryPicker() {
        final List<Uri> all = queryRecentImages();
        if (all.isEmpty()) {
            Toast.makeText(this, "相册里没有可读的照片,改用系统选择器", Toast.LENGTH_SHORT).show();
            pickImagesViaSystem();
            return;
        }

        final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final java.util.LinkedHashSet<Uri> picked = new java.util.LinkedHashSet<>(); // 保持点选顺序

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D0D0D);

        // 顶栏:取消 / 标题 / 系统选择器入口
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), dp(4), dp(4), dp(4));
        Button btnDlgCancel = new Button(this);
        btnDlgCancel.setText("取消");
        btnDlgCancel.setBackgroundColor(Color.TRANSPARENT);
        btnDlgCancel.setTextColor(0xFFCFCFCF);
        btnDlgCancel.setOnClickListener(v -> dialog.dismiss());
        TextView dlgTitle = new TextView(this);
        dlgTitle.setText("选择照片");
        dlgTitle.setTextColor(0xFFE8E8E8);
        dlgTitle.setTextSize(14);
        dlgTitle.setGravity(Gravity.CENTER);
        Button btnSys = new Button(this);
        btnSys.setText("系统选择");
        btnSys.setBackgroundColor(Color.TRANSPARENT);
        btnSys.setTextColor(0xFFCFCFCF);
        btnSys.setTextSize(13);
        btnSys.setOnClickListener(v -> { dialog.dismiss(); pickImagesViaSystem(); });
        top.addView(btnDlgCancel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        top.addView(dlgTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        top.addView(btnSys, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(top);

        // 照片网格(4 列)
        final int cellW = getResources().getDisplayMetrics().widthPixels / 4;
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(4);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // 底栏:已选计数 + 添加按钮
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(12), dp(6), dp(12), dp(10));
        final TextView cnt = new TextView(this);
        cnt.setText("已选 0 张");
        cnt.setTextColor(0xFFAAAAAA);
        cnt.setTextSize(13);
        final Button btnAdd = new Button(this);
        btnAdd.setText("添加");
        btnAdd.setEnabled(false);
        bottom.addView(cnt, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        bottom.addView(btnAdd, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(bottom);

        for (Uri uri : all) {
            FrameLayout cell = new FrameLayout(this);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = cellW;
            lp.height = cellW;
            cell.setLayoutParams(lp);
            cell.setPadding(dp(1), dp(1), dp(1), dp(1));

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFF1C1C1E); // 解码完成前的占位底色
            cell.addView(iv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            TextView cb = new TextView(this);
            cb.setText("✓");
            cb.setGravity(Gravity.CENTER);
            cb.setTextSize(14);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    dp(24), dp(24), Gravity.TOP | Gravity.END);
            clp.setMargins(0, dp(4), dp(4), 0);
            cell.addView(cb, clp);
            styleCheck(cb, false);

            cell.setOnClickListener(v -> {
                if (!picked.remove(uri)) picked.add(uri);
                boolean on = picked.contains(uri);
                styleCheck(cb, on);
                GradientDrawable bd = new GradientDrawable();
                bd.setStroke(dp(2), CHECK_BLUE);
                iv.setForeground(on ? bd : null);
                cnt.setText("已选 " + picked.size() + " 张");
                btnAdd.setText(picked.isEmpty() ? "添加" : "添加 " + picked.size() + " 张");
                btnAdd.setEnabled(!picked.isEmpty());
            });

            decodePool.submit(() -> {
                Bitmap b = loadGalleryThumb(uri);
                if (b != null) runOnUiThread(() -> iv.setImageBitmap(b));
            });
            grid.addView(cell);
        }

        btnAdd.setOnClickListener(v -> {
            dialog.dismiss();
            loadImages(new ArrayList<>(picked));
        });

        dialog.setContentView(root);
        dialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (!uris.isEmpty()) loadImages(uris);
    }

    private void loadImages(List<Uri> uris) {
        Toast.makeText(this, "载入 " + uris.size() + " 张图片…", Toast.LENGTH_SHORT).show();
        List<Future<ImgItem>> futures = new ArrayList<>();
        for (Uri uri : uris) futures.add(decodePool.submit(() -> decodeThumb(uri)));
        executor.submit(() -> {
            List<ImgItem> loaded = new ArrayList<>();
            for (Future<ImgItem> f : futures) {
                try {
                    ImgItem it = f.get(); // 按选择顺序收集,解码本身已在 decodePool 并行
                    if (it != null) loaded.add(it);
                } catch (Throwable ignored) { }
            }
            runOnUiThread(() -> onImagesLoaded(loaded));
        });
    }

    /** 解码单张缩略图并读取尺寸/文件名,失败返回 null(后台线程调用) */
    private ImgItem decodeThumb(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleFor(Math.max(bounds.outWidth, bounds.outHeight), THUMB_MAX);
            Bitmap thumb;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                thumb = BitmapFactory.decodeStream(in, null, opts);
            }
            if (thumb == null) return null;

            ImgItem it = new ImgItem();
            it.uri = uri;
            it.origW = bounds.outWidth;   // 存储尺寸(EXIF 旋转前)
            it.origH = bounds.outHeight;
            it.name = queryDisplayName(uri);
            // 读取 EXIF 方向烘焙进 rotation:竖拍照片存储为横版 + 旋转标记,
            // 这样横竖判断、编辑画布、缩略图、导出全部按真实方向工作
            it.rotation = readExifRotation(uri);
            if (it.rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(it.rotation);
                Bitmap t = Bitmap.createBitmap(thumb, 0, 0, thumb.getWidth(), thumb.getHeight(), m, true);
                if (t != thumb) thumb.recycle();
                thumb = t;
            }
            it.thumb = thumb;
            it.landscape = thumb.getWidth() >= thumb.getHeight(); // 每张图按(旋转后)宽高自动预选方向
            return it;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读取 EXIF 旋转角度(0/90/180/270),无标记或读取失败返回 0 */
    private int readExifRotation(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            android.media.ExifInterface exif = new android.media.ExifInterface(in);
            switch (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL)) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    private void onImagesLoaded(List<ImgItem> loaded) {
        if (loaded.isEmpty()) {
            Toast.makeText(this, "没有可用的图片", Toast.LENGTH_LONG).show();
            return;
        }
        int firstNew = items.size(); // 网页版:追加后自动选中本次新增的第一张
        items.addAll(loaded);
        rebuildPicker();
        selectPhoto(firstNew);
    }

    /** 取消 = 清空所有已导入的照片(网页版 cancelBtn) */
    private void confirmClearAll() {
        if (items.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setMessage("清空所有已导入的照片?")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> clearBatch())
                .show();
    }

    private void clearBatch() {
        loadSeq++;
        for (ImgItem it : items) if (it.thumb != null) it.thumb.recycle();
        items.clear();
        currentIndex = -1;
        if (editBitmap != null) { editBitmap.recycle(); editBitmap = null; }
        emptyState.setVisibility(View.VISIBLE);
        cropView.setVisibility(View.GONE);
        options.setVisibility(View.GONE);
        confirmBar.setVisibility(View.GONE);
        rebuildPicker();
    }

    // ---------- 底部照片条 ----------

    /** 重建整条照片条(缩略图 + 裁剪范围叠加 + 勾选钮),保持横向滚动位置 */
    private void rebuildPicker() {
        final int scrollX = pickerScroll.getScrollX();
        pickerRow.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            pickerRow.addView(buildThumbCell(items.get(i), i));
        }
        pickerRow.addView(addThumb);
        pickerScroll.post(() -> pickerScroll.setScrollX(scrollX));
        updatePickerTexts();
    }

    private FrameLayout buildThumbCell(ImgItem it, int index) {
        FrameLayout cell = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(88), dp(88));
        lp.setMargins(dp(3), 0, dp(3), 0);
        cell.setLayoutParams(lp);

        // child 0: 缩略图(cover 裁剪显示)
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(it.thumb);
        cell.addView(iv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // child 1: 状态描边 — 勾选=蓝框,当前编辑=白框(与网页版 .thumb.selected/.current 一致)
        View border = new View(this);
        GradientDrawable bd = new GradientDrawable();
        if (it.checked) bd.setStroke(dp(2), CHECK_BLUE);
        else if (index == currentIndex) bd.setStroke(dp(2), Color.WHITE);
        border.setBackground(bd);
        border.setVisibility(it.checked || index == currentIndex ? View.VISIBLE : View.GONE);
        cell.addView(border, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // child 2: 裁剪范围叠加(框外压暗 + 黄框);thumb 已随旋转,直接用旋转后坐标系的归一化框
        ThumbCropOverlay overlay = new ThumbCropOverlay(this, it, normFor(it, ratioFor(it)));
        cell.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // child 3: 右上角圆形勾选钮(蓝底白勾 = 选中)
        TextView cb = new TextView(this);
        cb.setText("✓");
        cb.setGravity(Gravity.CENTER);
        cb.setTextSize(14);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                dp(24), dp(24), Gravity.TOP | Gravity.END);
        clp.setMargins(0, dp(4), dp(4), 0);
        cell.addView(cb, clp);
        styleCheck(cb, it.checked);

        cb.setOnClickListener(v -> {
            it.checked = !it.checked;
            rebuildPicker();
        });
        iv.setOnClickListener(v -> selectPhoto(index));
        overlay.setOnClickListener(v -> selectPhoto(index));
        return cell;
    }

    private void styleCheck(TextView cb, boolean checked) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        if (checked) {
            d.setColor(CHECK_BLUE);
            cb.setTextColor(Color.WHITE);
        } else {
            d.setColor(0x59000000);
            d.setStroke(dp(1.5f), 0xD9FFFFFF);
            cb.setTextColor(Color.TRANSPARENT);
        }
        cb.setBackground(d);
    }

    /** 缩略图上的裁剪范围叠加层:框外压暗 + 黄框,坐标变换与 CENTER_CROP 的 ImageView 一致 */
    private static class ThumbCropOverlay extends View {
        private final int tw, th;          // thumb 尺寸
        private final RectF norm;          // thumb 坐标系(= 旋转后坐标系)的归一化裁剪框
        private final RectF dst = new RectF();
        private final Paint mask = new Paint();
        private final Paint borderPaint = new Paint();

        ThumbCropOverlay(Context ctx, ImgItem it, RectF normRotated) {
            super(ctx);
            tw = it.thumb.getWidth();
            th = it.thumb.getHeight();
            norm = normRotated;
            mask.setColor(0x8C000000);
            borderPaint.setColor(0xFF66D9C0);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float vw = getWidth(), vh = getHeight();
            if (vw <= 0 || vh <= 0 || tw <= 0 || th <= 0) return;
            float scale = Math.max(vw / tw, vh / th); // CENTER_CROP
            float dw = tw * scale, dh = th * scale;
            dst.set((vw - dw) / 2, (vh - dh) / 2, (vw + dw) / 2, (vh + dh) / 2);
            RectF c = new RectF(
                    dst.left + norm.left * dw,
                    dst.top + norm.top * dh,
                    dst.left + norm.right * dw,
                    dst.top + norm.bottom * dh);
            canvas.save();
            canvas.clipRect(dst); // 压暗只落在可见图像区域
            canvas.drawRect(dst.left, dst.top, dst.right, c.top, mask);
            canvas.drawRect(dst.left, c.bottom, dst.right, dst.bottom, mask);
            canvas.drawRect(dst.left, c.top, c.left, c.bottom, mask);
            canvas.drawRect(c.right, c.top, dst.right, c.bottom, mask);
            canvas.restore();
            canvas.drawRect(c, borderPaint);
        }
    }

    private int checkedCount() {
        int n = 0;
        for (ImgItem it : items) if (it.checked) n++;
        return n;
    }

    /** 全选 / 全不选(网页版 selectAllBtn):有未勾选则全选,否则全部取消 */
    private void toggleAllPhotos() {
        if (items.isEmpty()) return;
        boolean anyUnchecked = false;
        for (ImgItem it : items) if (!it.checked) { anyUnchecked = true; break; }
        for (ImgItem it : items) it.checked = anyUnchecked;
        rebuildPicker();
    }

    private void updatePickerTexts() {
        int n = items.size();
        pickerHead.setText(n > 0
                ? "全部照片  " + n + " 张 · 已选 " + checkedCount() + "  ∨"
                : "全部照片 ∨");
        btnSelectAll.setText(n > 0 && checkedCount() == n ? "全不选" : "全选");
        btnExport.setEnabled(n > 0);
        pageInfo.setText(currentIndex >= 0 && currentIndex < n
                ? (currentIndex + 1) + " / " + n : "");
    }

    // ---------- 单张编辑 ----------

    /** 切换当前编辑的照片(网页版 selectPhoto):存回上一张的裁剪框,异步解码编辑位图 */
    private void selectPhoto(int index) {
        if (index < 0 || index >= items.size()) return;
        if (index == currentIndex && editBitmap != null) { updatePickerTexts(); return; }
        saveCurrentCrop();
        currentIndex = index;
        ImgItem it = items.get(index);
        int seq = ++loadSeq;
        executor.submit(() -> {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sampleFor(Math.max(it.origW, it.origH), EDIT_MAX);
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bmp;
                try (InputStream in = getContentResolver().openInputStream(it.uri)) {
                    bmp = BitmapFactory.decodeStream(in, null, opts);
                }
                if (bmp == null) throw new IllegalStateException("解码失败");
                if (it.rotation != 0) { // 编辑位图按记忆的角度旋转,裁剪框在旋转后坐标系下工作
                    Matrix m = new Matrix();
                    m.postRotate(it.rotation);
                    Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                    if (rotated != bmp) bmp.recycle();
                    bmp = rotated;
                }
                Bitmap result = bmp;
                runOnUiThread(() -> {
                    if (seq != loadSeq || !items.contains(it)) { result.recycle(); return; }
                    onEditReady(it, result);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this, "载入失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void onEditReady(ImgItem it, Bitmap bmp) {
        if (editBitmap != null) editBitmap.recycle();
        editBitmap = bmp;
        cropView.setRatio(ratioFor(it));
        cropView.setBitmap(bmp); // 内部会 resetCrop
        if (it.cropNorm != null) { // 恢复这张图上次的裁剪框
            cropView.setCrop(new RectF(
                    it.cropNorm.left * bmp.getWidth(),
                    it.cropNorm.top * bmp.getHeight(),
                    it.cropNorm.right * bmp.getWidth(),
                    it.cropNorm.bottom * bmp.getHeight()));
        }
        emptyState.setVisibility(View.GONE);
        cropView.setVisibility(View.VISIBLE);
        options.setVisibility(View.VISIBLE);
        confirmBar.setVisibility(View.VISIBLE);
        updateOrientButtons();
        updateBarsPreview(); // 恢复黑边实时预览状态
        rebuildPicker(); // 刷新「当前」白框与页码
    }

    /** 应用到全部:把当前照片的裁剪框(归一化)与横竖方向套用到所有照片(网页版 applyAllBtn) */
    private void applyCropToAll() {
        if (currentIndex < 0 || currentIndex >= items.size() || editBitmap == null) return;
        saveCurrentCrop();
        ImgItem src = items.get(currentIndex);
        for (ImgItem it : items) {
            if (it == src) continue;
            it.cropNorm = new RectF(src.cropNorm);
            it.landscape = src.landscape;
        }
        rebuildPicker();
        btnApplyAll.setText("已应用 ✓");
        btnApplyAll.postDelayed(() -> btnApplyAll.setText("应用到全部"), 1200);
    }

    // ---------- 旋转 ----------

    /** 旋转当前图 ±90°,按每张图记忆;缩略图一并旋转,裁剪框重置(与网页版 rotateCurrent 一致) */
    private void rotateCurrent(int deg) {
        if (editBitmap == null || currentIndex < 0 || currentIndex >= items.size()) return;
        ImgItem it = items.get(currentIndex);
        it.rotation = (it.rotation + deg + 360) % 360;
        Matrix m = new Matrix();
        m.postRotate(deg);
        Bitmap rotated = Bitmap.createBitmap(editBitmap, 0, 0,
                editBitmap.getWidth(), editBitmap.getHeight(), m, true);
        if (rotated != editBitmap) editBitmap.recycle();
        editBitmap = rotated;
        if (it.thumb != null) { // 缩略图保持与编辑画布同方向,叠加层才能直接用旋转后坐标
            Bitmap t = Bitmap.createBitmap(it.thumb, 0, 0,
                    it.thumb.getWidth(), it.thumb.getHeight(), m, true);
            if (t != it.thumb) it.thumb.recycle();
            it.thumb = t;
        }
        it.landscape = rotated.getWidth() >= rotated.getHeight();
        it.cropNorm = null;
        updateOrientButtons();
        cropView.setRatio(ratioFor(it));
        cropView.setBitmap(rotated); // 内部会 resetCrop
        saveCurrentCrop();
        rebuildPicker();
    }

    // ---------- 比例 chips ----------

    /** 圆形 chip:圆底 + 按宽高比画的小矩形示意 */
    private static class RatioChipCircle extends View {
        RatioDef def;
        boolean selected;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        RatioChipCircle(Context ctx, RatioDef def) {
            super(ctx);
            this.def = def;
            fill.setStyle(Paint.Style.FILL);
            stroke.setStyle(Paint.Style.STROKE);
        }

        private float d(float v) { return v * getResources().getDisplayMetrics().density; }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            fill.setColor(selected ? ACCENT : CIRCLE_BG);
            canvas.drawCircle(cx, cy, Math.min(getWidth(), getHeight()) / 2f, fill);

            stroke.setColor(selected ? ACCENT_TEXT : 0xFF999999);
            stroke.setStrokeWidth(d(1.5f));
            float box = d(26), w, h;
            boolean dashed = false;
            float rot = 0;
            switch (def.key) {
                case "orig":
                    w = h = d(22); dashed = true; break;
                case "free":
                    w = d(22); h = d(16); rot = -12; break;
                default:
                    double r = def.value;
                    if (r >= 1) { w = box; h = Math.max(d(8), (float) (box / r)); }
                    else { h = box; w = Math.max(d(8), (float) (box * r)); }
            }
            stroke.setPathEffect(dashed ? new DashPathEffect(new float[]{d(3), d(2.5f)}, 0) : null);
            canvas.save();
            if (rot != 0) canvas.rotate(rot, cx, cy);
            canvas.drawRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, stroke);
            canvas.restore();
        }
    }

    private void buildRatioChips() {
        ratios.clear();
        ratios.addAll(loadRatioLayout());
        if (ratioIndex >= ratios.size()) ratioIndex = fallbackRatioIndex();

        ratioChips.removeAllViews();
        for (int i = 0; i < ratios.size(); i++) {
            final int idx = i;
            final RatioDef def = ratios.get(i);
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER_HORIZONTAL);
            ratioChips.addView(chip, new LinearLayout.LayoutParams(
                    dp(68), LinearLayout.LayoutParams.WRAP_CONTENT));

            RatioChipCircle circle = new RatioChipCircle(this, def);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(56), dp(56));
            chip.addView(circle, clp);

            TextView label = new TextView(this);
            label.setText(def.label + (def.sub != null ? "\n" + def.sub : "\n"));
            label.setTextSize(11);
            label.setGravity(Gravity.CENTER);
            label.setLineSpacing(0, 1.3f);
            chip.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            chip.setOnClickListener(v -> selectRatio(idx));
            if (def.custom) { // 自定义比例:长按删除
                chip.setOnLongClickListener(v -> { confirmDeleteRatio(idx); return true; });
            }
        }

        // 末尾「+」:添加自定义比例
        LinearLayout addChip = new LinearLayout(this);
        addChip.setOrientation(LinearLayout.VERTICAL);
        addChip.setGravity(Gravity.CENTER_HORIZONTAL);
        ratioChips.addView(addChip, new LinearLayout.LayoutParams(
                dp(68), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView plus = new TextView(this);
        plus.setText("+");
        plus.setTextSize(24);
        plus.setTextColor(0xFF999999);
        plus.setGravity(Gravity.CENTER);
        GradientDrawable plusBg = new GradientDrawable();
        plusBg.setShape(GradientDrawable.OVAL);
        plusBg.setColor(CIRCLE_BG);
        plus.setBackground(plusBg);
        addChip.addView(plus, new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView addLabel = new TextView(this);
        addLabel.setText("自定义\n");
        addLabel.setTextSize(11);
        addLabel.setTextColor(0xFF999999);
        addLabel.setGravity(Gravity.CENTER);
        addLabel.setLineSpacing(0, 1.3f);
        addChip.addView(addLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        addChip.setOnClickListener(v -> showAddRatioDialog());

        // 末尾「管理」:排序 / 删除 / 恢复默认
        LinearLayout mgChip = new LinearLayout(this);
        mgChip.setOrientation(LinearLayout.VERTICAL);
        mgChip.setGravity(Gravity.CENTER_HORIZONTAL);
        ratioChips.addView(mgChip, new LinearLayout.LayoutParams(
                dp(68), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView mgIcon = new TextView(this);
        mgIcon.setText("☰");
        mgIcon.setTextSize(18);
        mgIcon.setTextColor(0xFF999999);
        mgIcon.setGravity(Gravity.CENTER);
        GradientDrawable mgBg = new GradientDrawable();
        mgBg.setShape(GradientDrawable.OVAL);
        mgBg.setColor(CIRCLE_BG);
        mgIcon.setBackground(mgBg);
        mgChip.addView(mgIcon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView mgLabel = new TextView(this);
        mgLabel.setText("管理\n");
        mgLabel.setTextSize(11);
        mgLabel.setTextColor(0xFF999999);
        mgLabel.setGravity(Gravity.CENTER);
        mgLabel.setLineSpacing(0, 1.3f);
        mgChip.addView(mgLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mgChip.setOnClickListener(v -> showManageDialog());
        refreshRatioChips();
    }

    private void refreshRatioChips() {
        for (int i = 0; i < ratios.size() && i < ratioChips.getChildCount(); i++) {
            LinearLayout chip = (LinearLayout) ratioChips.getChildAt(i);
            boolean sel = i == ratioIndex;
            RatioChipCircle circle = (RatioChipCircle) chip.getChildAt(0);
            circle.selected = sel;
            circle.invalidate();
            ((TextView) chip.getChildAt(1)).setTextColor(sel ? ACCENT : 0xFF999999);
        }
    }

    /** 网页版:点击比例 chip → resetCrop + render */
    private void selectRatio(int idx) {
        if (idx < 0 || idx >= ratios.size()) return;
        ratioIndex = idx;
        refreshRatioChips();
        if (editBitmap == null) return;
        cropView.setRatio(currentRatio());
        cropView.resetCrop();
        saveCurrentCrop();
        rebuildPicker();
    }

    // ---------- 比例布局:自定义 + 排序/删除/恢复默认(SharedPreferences 持久化) ----------

    private static final String PREF_RATIO_LAYOUT = "ratio_layout";

    /** 比例布局:b|key = 内置,c|label|value = 自定义;旧版 custom_ratios 自动迁移 */
    private List<RatioDef> loadRatioLayout() {
        String s = getPreferences(MODE_PRIVATE).getString(PREF_RATIO_LAYOUT, null);
        if (s != null && !s.isEmpty()) {
            List<RatioDef> list = new ArrayList<>();
            for (String e : s.split(";")) {
                String[] kv = e.split("\\|");
                if (kv.length == 2 && kv[0].equals("b")) {
                    for (RatioDef f : FIXED_RATIOS) if (f.key.equals(kv[1])) { list.add(f); break; }
                } else if (kv.length == 3 && kv[0].equals("c")) {
                    try {
                        double v = Double.parseDouble(kv[2]);
                        if (v > 0) list.add(new RatioDef(kv[2], kv[1], null, v, true));
                    } catch (NumberFormatException ignored) { }
                }
            }
            if (!list.isEmpty()) return list;
        }
        List<RatioDef> list = new ArrayList<>(java.util.Arrays.asList(FIXED_RATIOS));
        // 迁移旧版 custom_ratios
        String legacy = getPreferences(MODE_PRIVATE).getString("custom_ratios", "");
        for (String e : legacy.split(";")) {
            String[] kv = e.split("\\|");
            if (kv.length == 2) {
                try {
                    double v = Double.parseDouble(kv[1]);
                    if (v > 0) list.add(new RatioDef(kv[1], kv[0], null, v, true));
                } catch (NumberFormatException ignored) { }
            }
        }
        return list;
    }

    private void saveRatioLayout() {
        StringBuilder sb = new StringBuilder();
        for (RatioDef d : ratios) {
            if (sb.length() > 0) sb.append(";");
            if (d.custom) sb.append("c|").append(d.label).append("|").append(d.key);
            else sb.append("b|").append(d.key);
        }
        getPreferences(MODE_PRIVATE).edit().putString(PREF_RATIO_LAYOUT, sb.toString()).apply();
    }

    private int indexOfKey(String key) {
        for (int i = 0; i < ratios.size(); i++) if (ratios.get(i).key.equals(key)) return i;
        return -1;
    }

    /** 选中项被删后的回退:电影画幅优先,否则列表第一项 */
    private int fallbackRatioIndex() {
        int i = indexOfKey("cinematic");
        return i >= 0 ? i : 0;
    }

    /** 恢复默认:内置比例回到默认顺序,自定义比例保留并排在末尾 */
    private void restoreDefaultLayout() {
        List<RatioDef> customs = new ArrayList<>();
        for (RatioDef d : ratios) if (d.custom) customs.add(d);
        ratios.clear();
        ratios.addAll(java.util.Arrays.asList(FIXED_RATIOS));
        ratios.addAll(customs);
        ratioIndex = RATIO_DEFAULT;
        saveRatioLayout();
        buildRatioChips();
        applyCurrentRatio();
    }

    /** 选中比例可能变化时,重置裁剪框套用当前比例 */
    private void applyCurrentRatio() {
        if (editBitmap == null) return;
        cropView.setRatio(currentRatio());
        cropView.resetCrop();
        saveCurrentCrop();
        rebuildPicker();
    }

    /** 比例管理面板:↑↓ 排序、× 删除、恢复默认(与网页版 manageMask 一致) */
    private void showManageDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), 0, dp(16), 0);
        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(listBox);
        box.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(320)));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("管理裁剪比例")
                .setView(box)
                .setNegativeButton("恢复默认", null) // 点击后不关闭,下面覆盖
                .setPositiveButton("完成", null)
                .create();

        final Runnable renderRows = () -> renderManageRows(listBox, dialog);
        renderRows.run();
        dialog.show();
        // 恢复默认后不自动关闭面板,方便继续调整
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            restoreDefaultLayout();
            renderManageRows(listBox, dialog);
        });
    }

    private void renderManageRows(LinearLayout listBox, AlertDialog dialog) {
        listBox.removeAllViews();
        for (int i = 0; i < ratios.size(); i++) {
            final int idx = i;
            RatioDef d = ratios.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));

            TextView label = new TextView(this);
            label.setText(d.label + (d.sub != null ? "  " + d.sub : "") + (d.custom ? "  自定义" : ""));
            label.setTextColor(0xFFDDDDDD);
            label.setTextSize(14);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            row.addView(manageBtn("↑", idx > 0, v -> {
                ratios.add(idx - 1, ratios.remove(idx));
                // 选中项跟随内容而非位置
                ratioIndex = ratioIndex == idx ? idx - 1 : ratioIndex == idx - 1 ? idx : ratioIndex;
                saveRatioLayout(); buildRatioChips();
                renderManageRows(listBox, dialog);
            }));
            row.addView(manageBtn("↓", idx < ratios.size() - 1, v -> {
                ratios.add(idx + 1, ratios.remove(idx));
                ratioIndex = ratioIndex == idx ? idx + 1 : ratioIndex == idx + 1 ? idx : ratioIndex;
                saveRatioLayout(); buildRatioChips();
                renderManageRows(listBox, dialog);
            }));
            row.addView(manageBtn("×", true, v -> {
                ratios.remove(idx);
                if (ratioIndex == idx) ratioIndex = fallbackRatioIndex();
                else if (ratioIndex > idx) ratioIndex--;
                saveRatioLayout(); buildRatioChips(); applyCurrentRatio();
                renderManageRows(listBox, dialog);
            }));
            listBox.addView(row);
        }
    }

    private Button manageBtn(String text, boolean enabled, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setEnabled(enabled);
        b.setTextSize(14);
        b.setTextColor(0xFFBBBBBB);
        b.setBackgroundColor(CIRCLE_BG);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(32));
        lp.setMarginStart(dp(6));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    /** 添加自定义比例:宽 / 高 两个输入框,比例 = 宽 ÷ 高 */
    private void showAddRatioDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        final EditText inW = new EditText(this);
        inW.setHint("宽,如 21");
        inW.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        inW.setGravity(Gravity.CENTER);
        TextView colon = new TextView(this);
        colon.setText(" : ");
        colon.setTextSize(18);
        colon.setTextColor(0xFF999999);
        final EditText inH = new EditText(this);
        inH.setHint("高,如 9");
        inH.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        inH.setGravity(Gravity.CENTER);
        box.addView(inW, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        box.addView(colon);
        box.addView(inH, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        new AlertDialog.Builder(this)
                .setTitle("添加自定义比例")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("添加", (d, w) -> {
                    double rw, rh;
                    try {
                        rw = Double.parseDouble(inW.getText().toString().trim());
                        rh = Double.parseDouble(inH.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        rw = rh = 0;
                    }
                    if (!(rw > 0) || !(rh > 0) || rw / rh > 100) {
                        Toast.makeText(this, "请输入正确的宽和高,如 21 和 9", Toast.LENGTH_LONG).show();
                        return;
                    }
                    double v = rw / rh;
                    String label = inW.getText().toString().trim() + ":" + inH.getText().toString().trim();
                    RatioDef def = new RatioDef(String.valueOf(v), label, null, v, true);
                    for (RatioDef r : ratios) {
                        if (r.custom && r.key.equals(def.key)) {
                            Toast.makeText(this, "该比例已存在", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    ratios.add(def);
                    saveRatioLayout();
                    ratioIndex = ratios.size() - 1;
                    buildRatioChips();
                    applyCurrentRatio();
                })
                .show();
    }

    private void confirmDeleteRatio(int idx) {
        RatioDef def = ratios.get(idx);
        new AlertDialog.Builder(this)
                .setMessage("删除自定义比例 " + def.label + "?")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    ratios.remove(idx);
                    if (ratioIndex == idx) ratioIndex = fallbackRatioIndex();
                    else if (ratioIndex > idx) ratioIndex--;
                    saveRatioLayout();
                    buildRatioChips();
                    applyCurrentRatio();
                })
                .show();
    }

    // ---------- 参数 ----------

    /** 旋转后的图片尺寸(原图像素坐标系) */
    private static int rotW(ImgItem it) { return it.rotation % 180 == 0 ? it.origW : it.origH; }
    private static int rotH(ImgItem it) { return it.rotation % 180 == 0 ? it.origH : it.origW; }

    /** 某张图当前生效的宽高比(宽:高),null = 自由比例。与网页版 currentRatio() 一致。 */
    private Float ratioFor(ImgItem it, int idx) {
        RatioDef def = ratios.get(idx);
        if (def.key.equals("free")) return null;
        if (def.key.equals("orig")) return (float) rotW(it) / rotH(it); // 原始 = 图自身比例,不随方向翻转
        double r = def.value;
        return (float) (it.landscape ? r : 1.0 / r);
    }

    private Float ratioFor(ImgItem it) { return ratioFor(it, ratioIndex); }

    private Float currentRatio() {
        if (currentIndex < 0 || currentIndex >= items.size()) return (float) CINEMATIC;
        return ratioFor(items.get(currentIndex));
    }

    private void setOrientation(boolean land) {
        if (currentIndex < 0 || currentIndex >= items.size() || editBitmap == null) return;
        items.get(currentIndex).landscape = land;
        updateOrientButtons();
        cropView.setRatio(currentRatio());
        cropView.resetCrop(); // 网页版:切换方向 → resetCrop
        saveCurrentCrop();
        rebuildPicker();
    }

    private void updateOrientButtons() {
        boolean land = true;
        if (currentIndex >= 0 && currentIndex < items.size()) land = items.get(currentIndex).landscape;
        btnLandscape.setBackgroundColor(land ? ACCENT : CIRCLE_BG);
        btnLandscape.setTextColor(land ? ACCENT_TEXT : 0xFFCCCCCC);
        btnPortrait.setBackgroundColor(land ? CIRCLE_BG : ACCENT);
        btnPortrait.setTextColor(land ? 0xFFCCCCCC : ACCENT_TEXT);
    }

    /** 分辨率预设高亮与输入框数值联动(网页版 syncResChips) */
    private void syncResChips() {
        int v = (int) parse(outWidth);
        for (int i = 0; i < resBtns.length; i++) {
            boolean sel = RES_VALS[i] == v;
            resBtns[i].setBackgroundColor(sel ? ACCENT : CIRCLE_BG);
            resBtns[i].setTextColor(sel ? ACCENT_TEXT : 0xFFBBBBBB);
        }
    }

    /** 切换导出格式并刷新按钮高亮;PNG 无损时不显示质量滑杆 */
    private void setOutFormat(int fmt) {
        outFormat = fmt;
        for (int i = 0; i < fmtBtns.length; i++) {
            fmtBtns[i].setBackgroundColor(i == fmt ? ACCENT : CIRCLE_BG);
            fmtBtns[i].setTextColor(i == fmt ? ACCENT_TEXT : 0xFFBBBBBB);
        }
        qualityRow.setVisibility(fmt == 0 ? View.GONE : View.VISIBLE);
    }

    /** 压缩质量 50~100(仅 JPG/WebP 生效) */
    private int currentQuality() { return qualitySeek.getProgress() + 50; }

    private static String extFor(int fmt) { return fmt == 1 ? "jpg" : fmt == 2 ? "webp" : "png"; }

    private float parse(EditText et) {
        try { return Float.parseFloat(et.getText().toString()); }
        catch (Exception e) { return 0; }
    }

    /** 把当前编辑框存回对应图片项(归一化坐标,旋转后坐标系) */
    private void saveCurrentCrop() {
        if (currentIndex < 0 || currentIndex >= items.size() || editBitmap == null) return;
        RectF c = cropView.getCrop();
        items.get(currentIndex).cropNorm = new RectF(
                c.left / editBitmap.getWidth(),
                c.top / editBitmap.getHeight(),
                c.right / editBitmap.getWidth(),
                c.bottom / editBitmap.getHeight());
    }

    /** 未单独调整过的图片:按给定比例取最大居中裁剪框(归一化坐标) */
    private RectF defaultCropNormSnapshot(int W, int H, Float r) {
        float rr = r != null ? r : (float) W / H;
        float w, h;
        if ((float) W / H > rr) { h = H; w = H * rr; } else { w = W; h = W / rr; }
        return new RectF((W - w) / 2f / W, (H - h) / 2f / H, (W + w) / 2f / W, (H + h) / 2f / H);
    }

    /** 图片的归一化裁剪框(记忆值或默认居中框),旋转后坐标系 */
    private RectF normFor(ImgItem it, Float r) {
        return it.cropNorm != null
                ? new RectF(it.cropNorm)
                : defaultCropNormSnapshot(rotW(it), rotH(it), r);
    }

    // ---------- 输出尺寸计算(与网页版 renderOutput 一致) ----------

    /** 输出参数:{内容宽, 内容高, 黑边, 成品宽, 成品高};输入为(旋转后)原图像素坐标下的裁剪尺寸。 */
    private int[] computeOutputFor(float cwOrig, float chOrig, Float r, int target, float barPct) {
        float cw = cwOrig, ch = chOrig;
        if (r != null) {
            ch = cw / r;
            if (ch > chOrig) { ch = chOrig; cw = ch * r; }
        }
        float longSide = Math.max(cw, ch);
        float k = 1f;
        if (target > 0 && longSide > target) k = target / longSide;
        else if (target <= 0 && longSide > OUT_MAX_DEFAULT) k = OUT_MAX_DEFAULT / longSide;
        int outW = Math.max(1, Math.round(cw * k));
        int outH = Math.max(1, Math.round(ch * k));

        int bar = Math.round(Math.max(outW, outH) * barPct / 100);
        boolean horiz = outW >= outH; // 黑边加在短边两侧
        int finalW = horiz ? outW : outW + bar * 2;
        int finalH = horiz ? outH + bar * 2 : outH;
        return new int[]{outW, outH, bar, finalW, finalH};
    }

    private float currentBarPct() {
        return barsCheck.isChecked() ? barsSeek.getProgress() / 100f : 0;
    }

    /** 把当前黑边设置同步到画布实时预览 */
    private void updateBarsPreview() {
        cropView.setBarPct(currentBarPct());
    }

    // ---------- 导出选项对话框 ----------

    private TextView dialogLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFAAAAAA);
        t.setTextSize(13);
        t.setPadding(0, dp(10), 0, dp(4));
        return t;
    }

    private Button dialogChip(LinearLayout row, String text, boolean marginStart) {
        Button b = new Button(this);
        b.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        if (marginStart) lp.setMarginStart(dp(4));
        row.addView(b, lp);
        return b;
    }

    private void highlightChips(Button[] btns, int sel) {
        for (int j = 0; j < btns.length; j++) {
            btns[j].setBackgroundColor(j == sel ? ACCENT : CIRCLE_BG);
            btns[j].setTextColor(j == sel ? ACCENT_TEXT : 0xFFCCCCCC);
        }
    }

    /** 导出前弹出分辨率/格式/质量选择,预填当前设置;确认后同步回选项面板并执行 onConfirm */
    private void showExportDialog(Runnable onConfirm) {
        final int[] res = {(int) parse(outWidth)};
        final int[] fmt = {outFormat};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);

        // 分辨率
        box.addView(dialogLabel("分辨率(长边)"));
        LinearLayout resRow = new LinearLayout(this);
        box.addView(resRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        final String[] resNames = {"原始", "4K", "2K", "1080P"};
        final Button[] dlgResBtns = new Button[RES_VALS.length];
        for (int i = 0; i < RES_VALS.length; i++) {
            final int idx = i;
            dlgResBtns[i] = dialogChip(resRow, resNames[i], i > 0);
            dlgResBtns[i].setOnClickListener(v -> {
                res[0] = RES_VALS[idx];
                highlightChips(dlgResBtns, idx);
            });
        }
        int selRes = -1;
        for (int i = 0; i < RES_VALS.length; i++) if (RES_VALS[i] == res[0]) selRes = i;
        highlightChips(dlgResBtns, selRes);

        // 格式
        box.addView(dialogLabel("格式"));
        LinearLayout fmtRow = new LinearLayout(this);
        box.addView(fmtRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        final String[] fmtNames = {"JPG", "PNG 无损", "WebP"};
        final int[] fmtVals = {1, 0, 2}; // 显示顺序 → 格式值(0=PNG 1=JPG 2=WebP)
        final Button[] dlgFmtBtns = new Button[fmtNames.length];

        // 压缩质量(选 PNG 时隐藏)
        TextView qLabel = dialogLabel("压缩质量 " + currentQuality() + "%");
        SeekBar qSeek = new SeekBar(this);
        qSeek.setMax(50);
        qSeek.setProgress(currentQuality() - 50);
        qSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                qLabel.setText("压缩质量 " + (progress + 50) + "%");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        for (int i = 0; i < fmtNames.length; i++) {
            final int idx = i;
            dlgFmtBtns[i] = dialogChip(fmtRow, fmtNames[i], i > 0);
            dlgFmtBtns[i].setOnClickListener(v -> {
                fmt[0] = fmtVals[idx];
                highlightChips(dlgFmtBtns, idx);
                int vis = fmtVals[idx] == 0 ? View.GONE : View.VISIBLE;
                qLabel.setVisibility(vis);
                qSeek.setVisibility(vis);
            });
        }
        int selFmt = 0;
        for (int i = 0; i < fmtVals.length; i++) if (fmtVals[i] == fmt[0]) selFmt = i;
        highlightChips(dlgFmtBtns, selFmt);
        if (fmt[0] == 0) { qLabel.setVisibility(View.GONE); qSeek.setVisibility(View.GONE); }

        box.addView(qLabel);
        box.addView(qSeek, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("导出设置")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始导出", (d, w) -> {
                    outWidth.setText(String.valueOf(res[0])); // 同步回选项面板
                    setOutFormat(fmt[0]);
                    qualitySeek.setProgress(qSeek.getProgress());
                    onConfirm.run();
                })
                .show();
    }

    // ---------- 导出 ----------

    /** 网页版 exportList:导出勾选的图片,未勾选任何时导出当前这张 */
    private void exportSelected() {
        List<ImgItem> targets = new ArrayList<>();
        for (ImgItem it : items) if (it.checked) targets.add(it);
        if (targets.isEmpty()) {
            if (currentIndex < 0 || currentIndex >= items.size()) return;
            targets.add(items.get(currentIndex));
        }
        saveCurrentCrop(); // 正在编辑的图先落盘到状态
        // 在 UI 线程快照参数,后台线程不再读控件
        final int ratioIdx = ratioIndex;
        final int target = (int) parse(outWidth);
        final float barPct = currentBarPct();
        final int fmt = outFormat;
        final int quality = currentQuality();
        final int total = targets.size();

        btnExport.setEnabled(false);
        final Uri[] lastUri = new Uri[1]; // 最后一张的相册 Uri,用于「打开相册查看」
        executor.submit(() -> {
            int ok = 0, fail = 0;
            for (int i = 0; i < total; i++) {
                ImgItem it = targets.get(i);
                try {
                    Float r = ratioFor(it, ratioIdx);
                    RectF n = normFor(it, r);
                    float cw = n.width() * rotW(it), ch = n.height() * rotH(it);
                    int[] o = computeOutputFor(cw, ch, r, target, barPct);
                    Rect region = cropRegionOrig(it, r);
                    String suffix = it.landscape ? "_cinematic" : "_cinematic_portrait";
                    lastUri[0] = exportOne(it.uri, region, it.rotation, o,
                            it.name + suffix + "." + extFor(fmt), fmt, quality);
                    ok++;
                } catch (Throwable t) {
                    fail++;
                }
                int done = i + 1;
                runOnUiThread(() -> btnExport.setText("导出 " + done + "/" + total));
            }
            int okF = ok, failF = fail;
            runOnUiThread(() -> {
                btnExport.setText("导出");
                btnExport.setEnabled(true);
                if (okF == 0) {
                    Toast.makeText(this, "导出失败", Toast.LENGTH_LONG).show();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("导出完成")
                        .setMessage("已保存 " + okF + " 张到相册"
                                + (failF > 0 ? ",失败 " + failF + " 张" : ""))
                        .setNegativeButton("完成", null)
                        .setPositiveButton("打开相册查看", (d, w) -> openInGallery(lastUri[0]))
                        .show();
            });
        });
    }

    /** 用系统相册/图库应用打开刚导出的图片 */
    private void openInGallery(Uri uri) {
        if (uri == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "没有找到可查看图片的应用", Toast.LENGTH_SHORT).show();
        }
    }

    /** 把(旋转后坐标系的)归一化裁剪框逆映射回原图区域,供 BitmapRegionDecoder 解码 */
    private Rect cropRegionOrig(ImgItem it, Float r) {
        RectF n = normFor(it, r);
        RectF cropRot = new RectF(
                n.left * rotW(it), n.top * rotH(it),
                n.right * rotW(it), n.bottom * rotH(it));
        if (it.rotation == 0) return clampRegion(cropRot, it.origW, it.origH);
        Matrix fwd = new Matrix();
        fwd.setRotate(it.rotation);
        RectF b = new RectF(0, 0, it.origW, it.origH);
        fwd.mapRect(b);
        fwd.postTranslate(-b.left, -b.top); // 与 Bitmap.createBitmap(..., matrix) 的边界平移一致
        Matrix inv = new Matrix();
        fwd.invert(inv);
        RectF cropOrig = new RectF();
        inv.mapRect(cropOrig, cropRot);
        return clampRegion(cropOrig, it.origW, it.origH);
    }

    private Rect clampRegion(RectF f, int W, int H) {
        return new Rect(
                Math.max(0, Math.round(f.left)),
                Math.max(0, Math.round(f.top)),
                Math.min(W, Math.round(f.right)),
                Math.min(H, Math.round(f.bottom)));
    }

    /** 按区域解码原图,转回编辑时的角度,加黑边合成,按所选格式写入相册(后台线程调用),返回图片 Uri */
    private Uri exportOne(Uri uri, Rect region, int rotation, int[] o, String fileName,
                          int fmt, int quality) throws Exception {
        int outW = o[0], outH = o[1], bar = o[2], finalW = o[3], finalH = o[4];
        boolean horiz = outW >= outH;

        int sample = 1;
        while (region.width() / (sample * 2) >= outW && region.height() / (sample * 2) >= outH) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap regionBmp;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(in, false);
            regionBmp = decoder.decodeRegion(region, opts);
            decoder.recycle();
        }
        if (regionBmp == null) throw new IllegalStateException("区域解码失败");

        if (rotation != 0) { // 解码区域在原图坐标系,转回旋转后坐标系再合成
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(regionBmp, 0, 0,
                    regionBmp.getWidth(), regionBmp.getHeight(), m, true);
            if (rotated != regionBmp) regionBmp.recycle();
            regionBmp = rotated;
        }

        Bitmap out = Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.BLACK);
        int dx = horiz ? 0 : bar, dy = horiz ? bar : 0;
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(regionBmp, null, new Rect(dx, dy, dx + outW, dy + outH), paint);
        regionBmp.recycle();

        Bitmap.CompressFormat compressFormat;
        String mime;
        if (fmt == 1) {
            compressFormat = Bitmap.CompressFormat.JPEG;
            mime = "image/jpeg";
        } else if (fmt == 2) {
            compressFormat = Build.VERSION.SDK_INT >= 30
                    ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
            mime = "image/webp";
        } else {
            compressFormat = Bitmap.CompressFormat.PNG;
            mime = "image/png";
        }

        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        v.put(MediaStore.Images.Media.MIME_TYPE, mime);
        if (Build.VERSION.SDK_INT >= 29) {
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/电影画幅裁剪");
        }
        Uri outUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (outUri == null) throw new IllegalStateException("无法创建文件");
        try (OutputStream os = getContentResolver().openOutputStream(outUri)) {
            if (os == null) throw new IllegalStateException("无法写入文件");
            out.compress(compressFormat, fmt == 0 ? 100 : quality, os); // PNG 的 quality 参数无效,固定 100
        }
        out.recycle();
        return outUri;
    }

    private int sampleFor(int longSide, int limit) {
        int sample = 1;
        while (longSide / (sample * 2) >= limit) sample *= 2;
        return sample;
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null) return n.replaceAll("\\.[^.]+$", "");
                }
            }
        } catch (Exception ignored) { }
        return "image";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        decodePool.shutdownNow();
        for (ImgItem it : items) if (it.thumb != null) it.thumb.recycle();
        if (editBitmap != null) { editBitmap.recycle(); editBitmap = null; }
    }
}
