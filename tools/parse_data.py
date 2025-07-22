import osmium
import sqlite3
import requests
from tqdm import tqdm
import os

PBF_URL = "https://download.geofabrik.de/europe/germany/bayern-latest.osm.pbf"
PBF_FILE = "./files/bavaria-latest.osm.pbf"
DB_PATH = "./files/speed_limits.sqlite"

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

class SpeedHandler(osmium.SimpleHandler):
    def __init__(self, db_cursor):
        super().__init__()
        self.db = db_cursor
        self.inserted = 0

    def way(self, w):
        if "highway" in w.tags and "maxspeed" in w.tags:
            try:
                coords = [(n.lon, n.lat) for n in w.nodes]
                if not coords:
                    return
                mid_idx = len(coords) // 2
                lon, lat = coords[mid_idx]
                speed = int(w.tags["maxspeed"].split()[0])  # crude parse
            except:
                return

            self.db.execute(
                "INSERT INTO speed_limits (id, lat, lon, speed_limit) VALUES (?, ?, ?, ?)",
                (w.id, lat, lon, speed)
            )
            self.inserted += 1

def init_db(path):
    conn = sqlite3.connect(path)
    c = conn.cursor()
    c.execute("DROP TABLE IF EXISTS speed_limits;")
    c.execute("""
        CREATE TABLE speed_limits (
            id INTEGER PRIMARY KEY,
            lat REAL,
            lon REAL,
            speed_limit INTEGER
        );
    """)
    c.execute("CREATE INDEX idx_lat ON speed_limits(lat);")
    c.execute("CREATE INDEX idx_lon ON speed_limits(lon);")
    return conn, c

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
