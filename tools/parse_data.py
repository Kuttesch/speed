import sqlite3
import os
import math
import osmium
import requests
from tqdm import tqdm

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

def haversine(lat1, lon1, lat2, lon2):
    R = 6371000  # meters
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = phi2 - phi1
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))

def interpolate_points(lat1, lon1, lat2, lon2, step=5):
    dist = haversine(lat1, lon1, lat2, lon2)
    if dist < step:
        return [(lat1, lon1), (lat2, lon2)]
    num_points = int(dist // step)
    return [
        (
            lat1 + (lat2 - lat1) * i / num_points,
            lon1 + (lon2 - lon1) * i / num_points
        )
        for i in range(num_points + 1)
    ]

class SpeedHandler(osmium.SimpleHandler):
    def __init__(self, db_cursor):
        super().__init__()
        self.db = db_cursor
        self.inserted = 0

    def way(self, w):
        if "highway" in w.tags and "maxspeed" in w.tags:
            speed_tag = w.tags["maxspeed"]
            try:
                speed = int(speed_tag.split()[0])
            except:
                return

            nodes = w.nodes
            for i in range(len(nodes) - 1):
                lat1, lon1 = nodes[i].lat, nodes[i].lon
                lat2, lon2 = nodes[i+1].lat, nodes[i+1].lon
                for lat, lon in interpolate_points(lat1, lon1, lat2, lon2, step=5):
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
            id INTEGER,
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
