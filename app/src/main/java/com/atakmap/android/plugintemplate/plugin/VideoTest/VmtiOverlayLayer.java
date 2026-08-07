package com.atakmap.android.plugintemplate.plugin.VideoTest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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

import java.util.Collections;
import java.util.Map;

import gov.tak.api.video.ConnectionEntry;



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
        // launched via a <__video sensor="..."/> CoT detail (which is how the marker gets linked to the video in the first place), unconfirmed for anything else.
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

    //Get all of the VMTI targets from the overall KLV metadata
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


        //The Partech decoder shipped with ATAK only supports:
            //Target ID
            //Centroid
            //Bounding box coords

        //ST0903 allows for more advanced fields such as
            //Confidence
            // priority
            // label

        //Custom KLV decoder for remaining ST0903 fields
        byte[] raw = rawData != null ? rawData.getValue() : null;
        overlay.setExtras(raw != null ? St0903KlvDecoder.parse(raw) : null);
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
            params.bottomMargin = (root.getHeight() - hostControlsTop);
            showButton.setLayoutParams(params);
        }

        void reset() {
            boxesView.setDataset(null);
            boxesView.setExtras(null);
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

        void setExtras(Map<Integer, St0903KlvDecoder.TargetExtra> extras) {
            boxesView.setExtras(extras);
        }

        void setPlatformMatched(boolean matched) {
            showButton.setVisibility(matched ? VISIBLE : GONE);
        }
    }


    //VMTI boxes
    private static class BoxesView extends View {

        private final Paint boxPaint = new Paint();
        private final Paint labelPaint = new Paint();
        private final Paint confidencePaint = new Paint();
        private final Paint priorityPaint = new Paint();

        private volatile Matrix viewMatrix;
        private volatile int videoWidth;
        private volatile int videoHeight;
        private volatile VMTIDataset dataset;
        private volatile Map<Integer, St0903KlvDecoder.TargetExtra> extras = Collections.emptyMap();

        private volatile boolean showVmti = false;

        BoxesView(Context context) {
            super(context);
            boxPaint.setColor(Color.RED);
            boxPaint.setStyle(Paint.Style.STROKE);
            boxPaint.setAntiAlias(true);

            labelPaint.setColor(Color.WHITE);
            labelPaint.setAntiAlias(true);
            labelPaint.setFakeBoldText(true);
            labelPaint.setShadowLayer(3f, 0, 0, Color.BLACK);

            // color set per-draw based on confidence tier, see confidenceColor()
            confidencePaint.setAntiAlias(true);
            confidencePaint.setFakeBoldText(true);
            confidencePaint.setShadowLayer(3f, 0, 0, Color.BLACK);

            priorityPaint.setColor(Color.rgb(255, 165, 0));
            priorityPaint.setAntiAlias(true);
            priorityPaint.setFakeBoldText(true);
            priorityPaint.setShadowLayer(3f, 0, 0, Color.BLACK);
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

        void setExtras(Map<Integer, St0903KlvDecoder.TargetExtra> e) {
            extras = e != null ? e : Collections.emptyMap();
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
            float textSize = Math.max(12f, frameWidth / 60f);
            labelPaint.setTextSize(textSize);
            confidencePaint.setTextSize(textSize);
            priorityPaint.setTextSize(textSize);

            canvas.save();


            //Scale target boxes to video screen size
            Matrix m = viewMatrix;
            if (m != null) canvas.concat(m);
            canvas.scale(getWidth() / (float) vw, getHeight() / (float) vh);
            canvas.scale(vw / (float) frameWidth, vh / (float) frameHeight);

            Map<Integer, St0903KlvDecoder.TargetExtra> extrasSnapshot = extras;
            for (VMTIDataset.Target t : ds.targets) {
                if (t != null)
                    drawTarget(canvas, t, frameWidth, frameHeight, extrasSnapshot);
            }

            canvas.restore();
        }

        // Draws one target: its box/contour (pgscmedia-decoded geometry, always available).
        //Then each ST0903.6 field pgscmedia doesn't decode (label/confidence/priority, recovered by St0903RawFields
        private void drawTarget(Canvas canvas, VMTIDataset.Target t,
                int frameWidth, int frameHeight,
                Map<Integer, St0903KlvDecoder.TargetExtra> extrasSnapshot) {
            float[] box = null;
            if (t.polygonPoints != null && t.polygonPoints.length >= 2)
                box = decodePolygon(t.polygonPoints, frameWidth);

            float anchorX;
            float anchorY;
            if (box != null && box.length == 4) {
                //Box defined as LL and UR coords
                drawBoundingBox(canvas, box[0], box[1], box[2], box[3]);
                anchorX = box[0];
                anchorY = box[1];
            } else if (box != null) {
                //Fully defined contour (more than 2 points)
                drawContour(canvas, box);
                anchorX = box[0];
                anchorY = box[1];
            } else {
                //Given a center location but no bounding box: small square, bound unknown
                float half = Math.max(frameWidth, frameHeight) / 40f;
                float cx = t.centroidPixelCol;
                float cy = t.centroidPixelRow;
                drawBoundingBox(canvas, cx - half, cy - half, cx + half, cy + half);
                anchorX = cx - half;
                anchorY = cy - half;
            }

            St0903KlvDecoder.TargetExtra extra = extrasSnapshot.get(t.targetId);
            float lineHeight = labelPaint.getTextSize() * 1.15f;
            float y = anchorY - 4;
            y -= drawObjectLabel(canvas, anchorX, y, t.targetId, extra, lineHeight);
            y -= drawConfidence(canvas, anchorX, y, extra, lineHeight);
            drawPriority(canvas, anchorX, y, extra, lineHeight);
        }

        private void drawBoundingBox(Canvas canvas, float left, float top, float right, float bottom) {
            canvas.drawRect(left, top, right, bottom, boxPaint);
        }

        private void drawContour(Canvas canvas, float[] points) {
            Path path = new Path();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2)
                path.lineTo(points[i], points[i + 1]);
            path.close();
            canvas.drawPath(path, boxPaint);
        }


        private float drawObjectLabel(Canvas canvas, float x, float y, int targetId,
                                      St0903KlvDecoder.TargetExtra extra, float lineHeight) {
            String label = extra != null && extra.label != null ? extra.label : ("Target " + targetId);
            canvas.drawText(label, x, y, labelPaint);
            return lineHeight;
        }


        private float drawConfidence(Canvas canvas, float x, float y,
                                     St0903KlvDecoder.TargetExtra extra, float lineHeight) {
            if (extra == null || extra.confidence < 0)
                return 0f;
            confidencePaint.setColor(confidenceColor(extra.confidence));
            canvas.drawText(extra.confidence + "% conf", x, y, confidencePaint);
            return lineHeight;
        }

        private float drawPriority(Canvas canvas, float x, float y,
                                   St0903KlvDecoder.TargetExtra extra, float lineHeight) {
            if (extra == null || extra.priority < 0)
                return 0f;
            canvas.drawText("priority " + extra.priority, x, y, priorityPaint);
            return lineHeight;
        }

        private static int confidenceColor(int confidencePercent) {
            if (confidencePercent >= 75)
                return Color.rgb(80, 220, 100);  // green: high confidence
            if (confidencePercent >= 40)
                return Color.rgb(240, 200, 60);  // amber: medium confidence
            return Color.rgb(240, 80, 80);       // red: low confidence
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
