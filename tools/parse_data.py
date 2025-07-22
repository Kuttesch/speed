import osmium
import sqlite3
import requests
from tqdm import tqdm
import os

DB_PATH = "./files/speed_limits.sqlite"
PBF_URL = "https://download.geofabrik.de/europe/germany/bayern-latest.osm.pbf"
PBF_FILE = "./files/bavaria-latest.osm.pbf"

# Download OSM PBF
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

# Osmium handler
class SpeedHandler(osmium.SimpleHandler):
    def __init__(self, db_cursor):
        super().__init__()
        self.db = db_cursor
        self.inserted = 0

    def way(self, w):
        if "highway" in w.tags and "maxspeed" in w.tags:
            try:
                coords = [(n.lon, n.lat) for n in w.nodes]
            except Exception:
                return
            if not coords:
                return

            # Use midpoint of the geometry
            mid_idx = len(coords) // 2
            lon, lat = coords[mid_idx]

            try:
                speed = int(w.tags["maxspeed"].split()[0])  # crude parsing
            except:
                return

            self.db.execute("INSERT INTO speed_limits (id, lat, lon, speed_limit) VALUES (?, ?, ?, ?)",
                            (w.id, lat, lon, speed))
            self.db.execute("INSERT INTO geo_index (id, minLat, maxLat, minLon, maxLon) VALUES (?, ?, ?, ?, ?)",
                            (w.id, lat, lat, lon, lon))
            self.inserted += 1

# Init SQLite
def init_db(path):
    conn = sqlite3.connect(path)
    c = conn.cursor()
    c.execute("DROP TABLE IF EXISTS speed_limits;")
    c.execute("CREATE TABLE speed_limits (id INTEGER PRIMARY KEY, lat REAL, lon REAL, speed_limit INTEGER);")
    c.execute("CREATE VIRTUAL TABLE geo_index USING rtree(id, minLat, maxLat, minLon, maxLon);")
    return conn, c

# Main
if __name__ == "__main__":
    os.makedirs("./files", exist_ok=True)
    download_osm_pbf(PBF_URL, PBF_FILE)

    print("Initializing database...")
    conn, cur = init_db(DB_PATH)

    print("Parsing and inserting...")
    handler = SpeedHandler(cur)
    handler.apply_file(PBF_FILE, locations=True)
    conn.commit()

    print(f"Inserted {handler.inserted} entries.")
    conn.close()
