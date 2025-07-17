import osmium
import json
import requests
from tqdm import tqdm
import os

# Download Bavaria PBF directly
def download_osm_pbf(url, output_path):
    if os.path.exists(output_path):
        print(f"{output_path} already exists, skipping download.")
        return
    with requests.get(url, stream=True) as r:
        r.raise_for_status()
        total = int(r.headers.get("content-length", 0))
        with open(output_path, "wb") as f, tqdm(
            desc="Downloading", total=total, unit="B", unit_scale=True
        ) as pbar:
            for chunk in r.iter_content(chunk_size=8192):
                if chunk:
                    f.write(chunk)
                    pbar.update(len(chunk))

# Osmium handler to extract roads with maxspeed
class SpeedHandler(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.roads = []

    def way(self, w):
        if "highway" in w.tags and "maxspeed" in w.tags:
            coords = []
            try:
                for n in w.nodes:
                    coords.append([n.lon, n.lat])
            except Exception:
                return
            self.roads.append({
                "id": w.id,
                "maxspeed": w.tags["maxspeed"],
                "geometry": coords
            })

# Main script
if __name__ == "__main__":
    PBF_URL = "https://download.geofabrik.de/europe/germany/bayern-latest.osm.pbf"
    PBF_FILE = "./files/bavaria-latest.osm.pbf"
    OUTPUT_JSON = "./files/bavaria_maxspeed.json"

    download_osm_pbf(PBF_URL, PBF_FILE)

    print("Parsing PBF...")
    handler = SpeedHandler()
    handler.apply_file(PBF_FILE, locations=True)

    print(f"Extracted {len(handler.roads)} roads with maxspeed.")

    print(f"Writing to {OUTPUT_JSON}...")
    with open(OUTPUT_JSON, "w", encoding="utf-8") as f:
        json.dump(handler.roads, f, ensure_ascii=False, indent=2)

    print("Done.")
