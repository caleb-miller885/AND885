import json
from ultralytics import YOLO

model = YOLO("yolov8n.pt")

SRC = "sample_video.mp4"
FRAME_W, FRAME_H = 768, 432  # must match the source video's actual resolution

records = []
total_dets = 0
class_counts = {}

results = model.track(SRC, persist=True, tracker="bytetrack.yaml", verbose=False, stream=True)

for frame_idx, r in enumerate(results):
    targets = []
    boxes = r.boxes
    if boxes is not None and boxes.id is not None:
        ids = boxes.id.int().tolist()
        xyxy = boxes.xyxy.tolist()
        cls = boxes.cls.int().tolist()
        conf = boxes.conf.tolist()
        names = r.names
        for tid, box, c, cf in zip(ids, xyxy, cls, conf):
            x1, y1, x2, y2 = box
            label = names[c]
            targets.append({
                "id": tid,
                "bbox": [round(y1), round(x1), round(y2), round(x2)],  # row_tl, col_tl, row_br, col_br
                "label": label,
                "confidence": round(cf * 100),
            })
            class_counts[label] = class_counts.get(label, 0) + 1
            total_dets += 1

    records.append({
        "frame_width": FRAME_W,
        "frame_height": FRAME_H,
        "targets": targets,
    })

with open("yolo_records.json", "w") as f:
    json.dump(records, f)

print(f"frames={len(records)} total_detections={total_dets}")
print("class counts:", class_counts)
print("frames with >=1 target:", sum(1 for r in records if r["targets"]))
