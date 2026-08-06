package com.atakmap.android.plugintemplate.plugin.VideoTest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.plugintemplate.plugin.Constants;
import com.atakmap.android.video.VideoViewLayer;
import com.partech.pgscmedia.frameaccess.DecodedMetadataItem;
import com.partech.pgscmedia.frameaccess.KLVData;
import com.partech.pgscmedia.frameaccess.VMTIDataset;

import java.util.Map;

import gov.tak.api.video.ConnectionEntry;

/**
 * Draws VMTI target boxes on top of the video, using the VMTI data ATAK pulls out of the KLV metadata (ST0601 tag 74 -> nested ST0903 VMTI Local Set).
 *
 * Registered once via VideoDropDownReceiver.registerVideoViewLayer(...) in main plugin class
 * onStart(). The VMTI layer only exist at all if the video's linked MapItem has the specified platform meta-tag - see start() below.
 *
 * VMTIDataset:
 *  - frameWidth / frameHeight: the pixel size the target coords below are relative to
 *  - timestamp, systemName, sensorName: not using these
 *  - targets[]: one per detected target this frame, each one has:
 *      - targetId: numeric id
 *      - centroidPixelRow / centroidPixelCol: center point, already split into row/col for us
 *      - centroidPixelLoc: same center point but packed into one number (see
 *        checkPolygonPacking() below, that's the annoying bit)
 *      - polygonPoints: the box corners, packed the same way as centroidPixelLoc - this is
 *        what actually becomes the box you see on screen
 *      - lat / lon / haeMeters: real world position if the sensor sends it, not drawing this,
 *
 */
public class VmtiOverlayLayer extends VideoViewLayer {

    private final VmtiOverlayView overlay;

    public VmtiOverlayLayer(Context context) {
        this(new VmtiOverlayView(context));
    }

    private VmtiOverlayLayer(VmtiOverlayView view) {
        super("cuas-vmti-overlay", view,
                new RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.MATCH_PARENT,
                        RelativeLayout.LayoutParams.MATCH_PARENT),
                true);
        this.overlay = view;
    }

    @Override
    public void start(ConnectionEntry connectionEntry, boolean hasMetadata) {
        enableStructuredDecoding(); // off by default - without this VMTI never gets decoded at all
        overlay.reset();

        // Assumes ConnectionEntry.getUID() matches the marker's own UID - true for videos
        // launched via a <__video sensor="..."/> CoT detail (which is how the marker gets
        // linked to the video in the first place), unconfirmed for anything else.
        String uid = connectionEntry != null ? connectionEntry.getUID() : null;
        MapItem item = uid != null ? MapView.getMapView().getRootGroup().deepFindUID(uid) : null;
        overlay.setPlatformMatched(item != null && item.hasMetaValue(Constants.UAS_ITEM));
    }

    @Override
    public void stop(ConnectionEntry connectionEntry) {
        overlay.reset();
    }

    @Override
    public void videoSizeChanged(int w, int h) {
        overlay.setVideoSize(w, h);
    }

    @Override
    public void setViewMatrix(Matrix matrix) {
        overlay.setViewMatrix(new Matrix(matrix)); // ATAK reuses its own Matrix object
    }

    @Override
    public void metadataChanged(final KLVData rawData,
            final Map<DecodedMetadataItem.MetadataItemIDs, DecodedMetadataItem> items) {
        if (items == null)
            return;

        DecodedMetadataItem item = items.get(
                DecodedMetadataItem.MetadataItemIDs.METADATA_ITEMID_VMTI_TARGET_SET);
        if (item == null)
            return;

        Object value = item.getValue();
        if (!(value instanceof VMTIDataset))
            return;

        overlay.setDataset((VMTIDataset) value);
    }

    //Rendered layer over top of the video feed, contains VMTI boxes along with the toggle button
    private static class VmtiOverlayView extends FrameLayout {

        private final BoxesView boxesView;
        private final Button showButton;


        //Set host control location to undefined until found
        private int hostControlsTop = -1;

        VmtiOverlayView(Context context) {
            super(context);

            boxesView = new BoxesView(context);
            addView(boxesView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            Drawable buttonBackground = MapView.getMapView().getContext().getDrawable(com.atakmap.app.R.drawable.btn_gray);

            showButton = new Button(context);
            showButton.setText("Show VMTI");
            showButton.setTextColor(Color.WHITE);
            showButton.setTypeface(Typeface.DEFAULT_BOLD);
            showButton.setBackground(buttonBackground);
            showButton.setVisibility(GONE); // only shown once video feed is confirmed from one of our platforms
            showButton.setOnClickListener(v -> {
                boolean nowOn = !boxesView.isShowVmti();
                boxesView.setShowVmti(nowOn);
                showButton.setSelected(nowOn);
            });

            FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            buttonParams.bottomMargin = dp(56); // rough default until positionButtonAvoidingHostControls() refines it
            buttonParams.rightMargin = dp(2);
            addView(showButton, buttonParams);
        }

        private int dp(int value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (hostControlsTop < 0)
                positionButtonAvoidingHostControls();
        }

        private void positionButtonAvoidingHostControls() {
            Object parent = getParent();
            Object grandparent = parent instanceof View ? ((View) parent).getParent() : null;
            if (!(grandparent instanceof android.view.ViewGroup))
                return;
            android.view.ViewGroup root = (android.view.ViewGroup) grandparent;
            if (root.getHeight() == 0)
                return; // root itself not laid out yet - try again next layout pass

            int highestBottomOccupantTop = root.getHeight();
            boolean found = false;
            //Search video overlay manager for existing buttons to avoid overlap
            for (int i = 0; i < root.getChildCount(); i++) {
                View child = root.getChildAt(i);
                if (child == parent || child.getVisibility() != VISIBLE)
                    continue; // skip our own container (it holds this button, don't avoid ourselves)
                if (child.getBottom() == 0 && child.getTop() == 0)
                    continue; // this sibling isn't laid out yet either - don't trust a 0
                boolean nearBottom = child.getBottom() > root.getHeight() * 3 / 4;
                boolean barShaped = child.getHeight() < root.getHeight() / 4;
                if (nearBottom && barShaped) {
                    highestBottomOccupantTop = Math.min(highestBottomOccupantTop, child.getTop());
                    found = true;
                }
            }
            if (!found)
                return; // nothing measured yet - try again next layout pass

            hostControlsTop = highestBottomOccupantTop;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) showButton.getLayoutParams();
            params.bottomMargin = (root.getHeight() - hostControlsTop) + dp(5);
            showButton.setLayoutParams(params);
        }

        void reset() {
            boxesView.setDataset(null);
            boxesView.setShowVmti(false);
            showButton.setSelected(false);
        }

        void setVideoSize(int w, int h) {
            boxesView.setVideoSize(w, h);
        }

        void setViewMatrix(Matrix matrix) {
            boxesView.setViewMatrix(matrix);
        }

        void setDataset(VMTIDataset ds) {
            boxesView.setDataset(ds);
        }

        void setPlatformMatched(boolean matched) {
            showButton.setVisibility(matched ? VISIBLE : GONE);
        }
    }


    //VMTI boxes
    private static class BoxesView extends View {

        private final Paint boxPaint = new Paint();
        private final Paint textPaint = new Paint();

        private volatile Matrix viewMatrix;
        private volatile int videoWidth;
        private volatile int videoHeight;
        private volatile VMTIDataset dataset;

        private volatile boolean showVmti = false;

        BoxesView(Context context) {
            super(context);
            boxPaint.setColor(Color.RED);
            boxPaint.setStyle(Paint.Style.STROKE);
            boxPaint.setAntiAlias(true);

            textPaint.setColor(Color.RED);
            textPaint.setAntiAlias(true);
            textPaint.setFakeBoldText(true);
        }

        boolean isShowVmti() {
            return showVmti;
        }

        void setShowVmti(boolean show) {
            showVmti = show;
            postInvalidate();
        }

        void setVideoSize(int w, int h) {
            videoWidth = w;
            videoHeight = h;
            postInvalidate();
        }

        void setViewMatrix(Matrix matrix) {
            viewMatrix = matrix;
            postInvalidate();
        }

        void setDataset(VMTIDataset ds) {
            dataset = ds;
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            VMTIDataset ds = dataset;
            int vw = videoWidth;
            int vh = videoHeight;

            if (showVmti && ds != null && vw > 0 && vh > 0 && ds.targets != null
                    && getWidth() > 0 && getHeight() > 0) {
                drawTargets(canvas, ds, vw, vh);
            }
        }

        private void drawTargets(Canvas canvas, VMTIDataset ds, int vw, int vh) {
            int frameWidth = ds.frameWidth > 0 ? ds.frameWidth : vw;
            int frameHeight = ds.frameHeight > 0 ? ds.frameHeight : vh;

            boxPaint.setStrokeWidth(Math.max(1f, frameWidth / 400f));
            textPaint.setTextSize(Math.max(12f, frameWidth / 60f));

            canvas.save();


            //Scale target boxes to video screen size
            Matrix m = viewMatrix;
            if (m != null) canvas.concat(m);
            canvas.scale(getWidth() / (float) vw, getHeight() / (float) vh);
            // Box coords below are drawn in "declared frame space" (0..frameWidth,
            // 0..frameHeight, from the KLV's own tags 8/9) - normally that's the same raster
            // as the actual decoded video (vw x vh), but isn't guaranteed to be (e.g. a real
            // sensor's declared frame size vs. what got decoded, or a test fixture authored
            // against a different resolution than what it's muxed alongside here). This
            // second scale converts frame-space into video-pixel-space before the stretch
            // above takes it the rest of the way to view-space; it's a no-op when
            // frameWidth/frameHeight already match vw/vh, which is the common case.
            canvas.scale(vw / (float) frameWidth, vh / (float) frameHeight);

            for (VMTIDataset.Target t : ds.targets) {
                if (t != null)
                    drawTarget(canvas, t, frameWidth, frameHeight);
            }

            canvas.restore();
        }

        private void drawTarget(Canvas canvas, VMTIDataset.Target t,
                int frameWidth, int frameHeight) {
            float[] box = null;
            if (t.polygonPoints != null && t.polygonPoints.length >= 2)
                box = decodePolygon(t.polygonPoints, frameWidth);

            //If box defined as LL and UR coords
            if (box != null && box.length == 4) {
                canvas.drawRect(box[0], box[1], box[2], box[3], boxPaint);
                canvas.drawText(String.valueOf(t.targetId), box[0], box[1] - 4, textPaint);

            //Handle fully defined box
            } else if (box != null) {
                Path path = new Path();
                path.moveTo(box[0], box[1]);
                for (int i = 2; i < box.length; i += 2)
                    path.lineTo(box[i], box[i + 1]);
                path.close();
                canvas.drawPath(path, boxPaint);
                canvas.drawText(String.valueOf(t.targetId), box[0], box[1] - 4, textPaint);

            //If given a center location but no bounding box just draw a small rect with classifier (bound unknown)
            } else {
                float half = Math.max(frameWidth, frameHeight) / 40f;
                float cx = t.centroidPixelCol;
                float cy = t.centroidPixelRow;
                canvas.drawRect(cx - half, cy - half, cx + half, cy + half, boxPaint);
                canvas.drawText(String.valueOf(t.targetId), cx - half, cy - half - 4, textPaint);
            }
        }

        // Target corners come as a single "pixel number" instead of (row, col): pixel 1 is the top-left corner of the frame.
        // Counting up left-to-right then wrapping to the next row.
        // Pixel number = row * frameWidth + col + 1 is the pixel at (row, col).
        // This undoes that: strip the +1, then integer-divide by frameWidth to get row back and take the remainder to get col back
        private float[] decodePolygon(long[] polygonPoints, int frameWidth) {
            float[] out = new float[polygonPoints.length * 2];
            for (int i = 0; i < polygonPoints.length; i++) {
                long idx = polygonPoints[i] - 1;
                if (idx < 0)
                    return null;
                out[i * 2] = idx % frameWidth;
                out[i * 2 + 1] = idx / frameWidth;
            }
            return out;
        }
    }
}
