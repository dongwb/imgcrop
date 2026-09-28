package com.cinematic.crop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 裁剪视图:贴降采样位图 + 遮罩/边框/手柄,支持拖动与缩放裁剪框。
 * 交互逻辑移植自 index.html(render / resizeCrop / applyRatioKeepCenter / resetCrop)。
 */
public class CropImageView extends View {

    public interface OnCropChangeListener {
        void onCropChangeFinished(); // 手势结束时回调(用于更新尺寸信息)
    }

    private static final float MIN_SIZE = 30f; // 位图像素

    private Bitmap bitmap;
    private final RectF crop = new RectF();     // 位图像素坐标
    private Float ratio = null;                 // 宽:高,null = 自由比例
    private float barPct = 0f;                  // 对称黑边厚度(占内容长边 %),0 = 不画
    private OnCropChangeListener listener;

    private final RectF dst = new RectF();      // 位图在视图中的显示区域
    private float viewScale = 1f;               // 位图像素 -> 视图像素

    private final RectF tmpCropView = new RectF();
    private final PointF[] handlePts = new PointF[8];     // 复用数组,避免绘制/触摸每帧分配
    private final PointF[] handlePtsView = new PointF[8];

    // 拖动状态
    private String dragHandle = null;           // null=未拖动;"move" 或手柄 id
    private final PointF dragStart = new PointF();
    private final RectF dragOrig = new RectF();

    private final Paint paintBitmap = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint paintMask = new Paint();
    private final Paint paintBorder = new Paint();
    private final Paint paintGrid = new Paint();
    private final Paint paintHandle = new Paint();
    private final Paint paintBar = new Paint();

    private static final String[] HANDLE_IDS = {"nw", "n", "ne", "e", "se", "s", "sw", "w"};

    public CropImageView(Context context) { super(context); init(); }
    public CropImageView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        paintMask.setColor(0x8C000000); // rgba(0,0,0,0.55)
        paintBorder.setColor(0xFF66D9C0);
        paintBorder.setStyle(Paint.Style.STROKE);
        paintBorder.setStrokeWidth(dp(1.5f));
        paintGrid.setColor(0x59FFFFFF); // rgba(255,255,255,0.35)
        paintGrid.setStyle(Paint.Style.STROKE);
        paintGrid.setStrokeWidth(dp(1f));
        paintHandle.setColor(0xFF66D9C0);
        paintBar.setColor(0xFF000000); // 黑边预览:纯黑
        for (int i = 0; i < 8; i++) {
            handlePts[i] = new PointF();
            handlePtsView[i] = new PointF();
        }
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    public void setOnCropChangeListener(OnCropChangeListener l) { listener = l; }

    public void setBitmap(Bitmap b) {
        bitmap = b;
        fitDst();
        resetCrop();
        invalidate();
    }

    public Bitmap getBitmap() { return bitmap; }

    /** 当前裁剪框(位图像素坐标)的副本 */
    public RectF getCrop() { return new RectF(crop); }

    /** 恢复裁剪框(位图像素坐标) */
    public void setCrop(RectF c) {
        crop.set(c);
        invalidate();
    }

    public void setRatio(Float r) { ratio = r; }

    /** 设置对称黑边厚度并立即重绘(拖动滑杆时实时预览) */
    public void setBarPct(float pct) { barPct = pct; invalidate(); }

    /** 最大内接比例框并居中(移植 resetCrop) */
    public void resetCrop() {
        if (bitmap == null) return;
        float W = bitmap.getWidth(), H = bitmap.getHeight();
        float r = ratio != null ? ratio : W / H;
        float w, h;
        if (W / H > r) { h = H; w = H * r; } else { w = W; h = W / r; }
        crop.set((W - w) / 2, (H - h) / 2, (W + w) / 2, (H + h) / 2);
        invalidate();
    }

    /** 保持裁剪框中心不变,套用当前比例并收进图像范围(移植 applyRatioKeepCenter) */
    public void applyRatioKeepCenter() {
        if (ratio == null || bitmap == null) return;
        float W = bitmap.getWidth(), H = bitmap.getHeight();
        float cx = crop.centerX(), cy = crop.centerY();
        float w = crop.width(), h = w / ratio;
        if (h > H) { h = H; w = h * ratio; }
        if (w > W) { w = W; h = w / ratio; }
        float x = Math.min(Math.max(cx - w / 2, 0), W - w);
        float y = Math.min(Math.max(cy - h / 2, 0), H - h);
        crop.set(x, y, x + w, y + h);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        fitDst();
    }

    private void fitDst() {
        if (bitmap == null || getWidth() == 0) return;
        float vw = getWidth(), vh = getHeight();
        float bw = bitmap.getWidth(), bh = bitmap.getHeight();
        viewScale = Math.min(vw / bw, vh / bh);
        float dw = bw * viewScale, dh = bh * viewScale;
        dst.set((vw - dw) / 2, (vh - dh) / 2, (vw + dw) / 2, (vh + dh) / 2);
    }

    private RectF cropViewRect() {
        tmpCropView.set(
                dst.left + crop.left * viewScale,
                dst.top + crop.top * viewScale,
                dst.left + crop.right * viewScale,
                dst.top + crop.bottom * viewScale);
        return tmpCropView;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap == null) return;
        canvas.drawBitmap(bitmap, null, dst, paintBitmap);

        RectF c = cropViewRect();
        // 遮罩(4 个矩形)
        canvas.drawRect(dst.left, dst.top, dst.right, c.top, paintMask);
        canvas.drawRect(dst.left, c.bottom, dst.right, dst.bottom, paintMask);
        canvas.drawRect(dst.left, c.top, c.left, c.bottom, paintMask);
        canvas.drawRect(c.right, c.top, dst.right, c.bottom, paintMask);

        // 对称黑边实时预览:成品 = 内容 + 短边两侧的黑边(与导出合成一致)
        if (barPct > 0) {
            float bar = Math.max(c.width(), c.height()) * barPct / 100f;
            boolean horiz = c.width() >= c.height();
            canvas.save();
            canvas.clipRect(dst); // 黑边不画出图像范围
            if (horiz) {
                canvas.drawRect(c.left, c.top - bar, c.right, c.top, paintBar);
                canvas.drawRect(c.left, c.bottom, c.right, c.bottom + bar, paintBar);
            } else {
                canvas.drawRect(c.left - bar, c.top, c.left, c.bottom, paintBar);
                canvas.drawRect(c.right, c.top, c.right + bar, c.bottom, paintBar);
            }
            canvas.restore();
        }

        // 边框 + 三分线
        canvas.drawRect(c, paintBorder);
        for (int i = 1; i <= 2; i++) {
            float x = c.left + c.width() * i / 3;
            float y = c.top + c.height() * i / 3;
            canvas.drawLine(x, c.top, x, c.bottom, paintGrid);
            canvas.drawLine(c.left, y, c.right, y, paintGrid);
        }

        // 手柄
        float hs = dp(5);
        for (PointF p : handlePointsView()) {
            canvas.drawRect(p.x - hs, p.y - hs, p.x + hs, p.y + hs, paintHandle);
        }
    }

    /** 8 个手柄的位图坐标(复用数组,仅在 UI 线程使用) */
    private PointF[] handlePoints() {
        float x = crop.left, y = crop.top, r = crop.right, b = crop.bottom;
        float cx = crop.centerX(), cy = crop.centerY();
        handlePts[0].set(x, y);   handlePts[1].set(cx, y); handlePts[2].set(r, y);
        handlePts[3].set(r, cy);
        handlePts[4].set(r, b);   handlePts[5].set(cx, b);
        handlePts[6].set(x, b);   handlePts[7].set(x, cy);
        return handlePts;
    }

    private PointF[] handlePointsView() {
        PointF[] pts = handlePoints();
        for (int i = 0; i < pts.length; i++) {
            handlePtsView[i].set(dst.left + pts[i].x * viewScale, dst.top + pts[i].y * viewScale);
        }
        return handlePtsView;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bitmap == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float px = (e.getX() - dst.left) / viewScale;
                float py = (e.getY() - dst.top) / viewScale;
                float tol = dp(28) / viewScale; // 触摸容差(位图像素)
                String hit = null;
                PointF[] pts = handlePoints();
                for (int i = 0; i < pts.length; i++) {
                    if (Math.abs(px - pts[i].x) < tol && Math.abs(py - pts[i].y) < tol) {
                        hit = HANDLE_IDS[i];
                        break;
                    }
                }
                if (hit != null) {
                    dragHandle = hit;
                } else if (crop.contains(px, py)) {
                    dragHandle = "move";
                } else {
                    return false;
                }
                dragStart.set(px, py);
                dragOrig.set(crop);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragHandle == null) return false;
                float px = (e.getX() - dst.left) / viewScale;
                float py = (e.getY() - dst.top) / viewScale;
                float W = bitmap.getWidth(), H = bitmap.getHeight();
                if ("move".equals(dragHandle)) {
                    float nx = dragOrig.left + (px - dragStart.x);
                    float ny = dragOrig.top + (py - dragStart.y);
                    nx = Math.min(Math.max(nx, 0), W - dragOrig.width());
                    ny = Math.min(Math.max(ny, 0), H - dragOrig.height());
                    crop.set(nx, ny, nx + dragOrig.width(), ny + dragOrig.height());
                } else {
                    resizeCrop(dragHandle, px, py, W, H);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (dragHandle != null) {
                    dragHandle = null;
                    if (listener != null) listener.onCropChangeFinished();
                    return true;
                }
                return false;
            }
        }
        return super.onTouchEvent(e);
    }

    /** 移植 index.html 的 resizeCrop:锚定对角/对边,按比例收缩并钳制在图像内 */
    private void resizeCrop(String handle, float px, float py, float W, float H) {
        RectF o = dragOrig;

        if (ratio == null) {
            // 自由比例
            float x = o.left, y = o.top, r = o.right, b = o.bottom;
            if (handle.contains("e")) r = px;
            if (handle.contains("w")) x = px;
            if (handle.contains("s")) b = py;
            if (handle.contains("n")) y = py;
            if (r - x >= MIN_SIZE) {
                x = Math.max(0, Math.min(x, o.right - MIN_SIZE));
                r = Math.min(W, Math.max(r, x + MIN_SIZE));
                crop.left = x; crop.right = r;
            }
            if (b - y >= MIN_SIZE) {
                y = Math.max(0, Math.min(y, o.bottom - MIN_SIZE));
                b = Math.min(H, Math.max(b, y + MIN_SIZE));
                crop.top = y; crop.bottom = b;
            }
            return;
        }

        // 锚点
        float ax, ay;
        switch (handle) {
            case "nw": ax = o.right; ay = o.bottom; break;
            case "se": ax = o.left; ay = o.top; break;
            case "ne": ax = o.left; ay = o.bottom; break;
            case "sw": ax = o.right; ay = o.top; break;
            case "n": ax = o.centerX(); ay = o.bottom; break;
            case "s": ax = o.centerX(); ay = o.top; break;
            case "e": ax = o.left; ay = o.centerY(); break;
            default: ax = o.right; ay = o.centerY(); break; // "w"
        }
        boolean edge = handle.length() == 1;

        float w, h;
        if (edge && (handle.equals("n") || handle.equals("s"))) {
            h = Math.abs(py - ay); w = h * ratio;
        } else if (edge) {
            w = Math.abs(px - ax); h = w / ratio;
        } else {
            float w1 = Math.abs(px - ax), h1 = Math.abs(py - ay);
            if (w1 / ratio > h1) { w = w1; h = w / ratio; } else { h = h1; w = h * ratio; }
        }
        w = Math.max(w, MIN_SIZE); h = Math.max(h, MIN_SIZE);

        // 以锚点定位,并收缩到图像范围内
        float nx = handle.contains("e") ? ax : edge ? ax - w / 2 : ax - w;
        float ny = handle.contains("s") ? ay
                : edge && (handle.equals("e") || handle.equals("w")) ? ay - h / 2 : ay - h;
        if (nx < 0 || ny < 0 || nx + w > W || ny + h > H) {
            float k = 1f;
            if (nx < 0) k = Math.min(k, ax / w);
            if (ny < 0) k = Math.min(k, ay / h);
            if (nx + w > W) k = Math.min(k, (W - ax) / (nx + w - ax));
            if (ny + h > H) k = Math.min(k, (H - ay) / (ny + h - ay));
            w *= k; h = w / ratio;
            nx = handle.contains("e") ? ax : edge ? ax - w / 2 : ax - w;
            ny = handle.contains("s") ? ay
                    : edge && (handle.equals("e") || handle.equals("w")) ? ay - h / 2 : ay - h;
        }
        nx = Math.max(0, nx);
        ny = Math.max(0, ny);
        crop.set(nx, ny, Math.min(nx + w, W), Math.min(ny + h, H));
    }
}
