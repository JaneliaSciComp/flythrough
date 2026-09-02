# Flythrough

A GUI-driven tool for authoring fly-through movies of large 3D
microscopy volumes stored as **N5** or **OME-Zarr** (including Zarr v3). It is heavily inspired by the hand-edit movie creation workflow in hot-knife.


The general workflow is as follows:
```
setup → intensity/CLAHE preview → interactive keyframe capture → visual verify/reorder → JSON config → headless PNG render
```

## Build

```bash
mvn clean package
```

produces a runnable uber-jar at `target/flythrough-*.jar`.

## Usage

### 1. Author a movie (GUI)

```bash
java -jar target/flythrough-0.0.1-SNAPSHOT.jar
```

- **Setup**: enter the data path, output (movie) directory, expansion factor,
  canvas size and FPS. The two path fields accept files/folders dropped from the
  desktop.
- **Intensity**: a BigDataViewer window opens with the defaults. Adjust
  normalization, CLAHE slope, clip min/max and invert, press **Reload preview** to
  see them (the current view is kept), and **OK** when you are happy.
- **Navigate**: the same BigDataViewer window; Pan / zoom / scroll to a view you
  like and press **T** to capture it as a key point. Key points are stored, in
  order, as the full viewer transform, so rotation is preserved.
- **Verify**: each key point gets a thumbnail. Set the number of **frames** and
  the **acceleration** curve for each segment between key points, optionally
  **hold** on the first key point, and optionally **return to the first key
  point** at the end. Drag thumbnails to reorder. Save the config and/or render.

### 2. Render from a config (headless)

```bash
java -jar target/flythrough-0.0.1-SNAPSHOT.jar --config movie-config.json
```

Writes `img-0000.png`, `img-0001.png`, … into an `images/` subfolder of the
movie directory (so the frames stay separate from `movie-config.json`).
Assemble into a video with e.g. ffmpeg:

```bash
cd <moviePath>/images
ffmpeg -r 30 -i img-%04d.png -c:v libx264 -pix_fmt yuv420p ../movie.mp4
```

## Config file

`movie-config.json` (written to the movie directory) is hand-editable for fine
control:

```json
{
  "dataPath": "/path/to/fused.ome.zarr",
  "moviePath": "/path/to/output",
  "expansionFactor": 30.0,
  "normalization": "CLAHE",
  "claheSlope": 1.5,
  "histogramMin": 250,
  "histogramMax": 2000,
  "screenWidth": 1050,
  "screenHeight": 750,
  "fps": 30,
  "holdFirstFrames": 120,
  "returnToFirst": true,
  "returnFrames": 120,
  "returnAccel": 3,
  "keyPoints": [
    { "transform": [ 0.117432, 0.0, 0.0, -4875.53, 0.0, 0.117432, 0.0, -393.13, 0.0, 0.0, 0.117432, -3181.94 ] }
  ],
  "segments": [
    { "frames": 0, "accel": 0 }
  ]
}
```

- `keyPoints[i].transform` is the full viewer transform as 12 row-packed doubles
  (`m00, m01, m02, m03, m10, …, m23`). Storing the full matrix — rather than a
  centre + zoom — preserves rotation and any orientation of the captured view.
  It is the movie-space viewer transform with the canvas-centre translation
  removed; the renderer adds the canvas centre back, so it is independent of the
  movie canvas size.
- `segments[k]` (`frames` + `accel`) is the motion **into** `keyPoints[k]`, i.e.
  from `keyPoints[k-1]` → `keyPoints[k]`. The list is kept the same length as
  `keyPoints` so each key point owns its incoming timing. **`segments[0]` is never
  read** — `keyPoints[0]` is the start of the movie and has no incoming motion, so
  it is always written as `{ "frames": 0, "accel": 0 }`. To pause on the first key
  point, use `holdFirstFrames`, not `segments[0]`.
- Acceleration types: `0` symmetric, `1` slow start, `2` slow end,
  `3` soft symmetric, `4` soft slow start, `5` soft slow end.

Per-level scale / anisotropy is read automatically from the dataset's OME-NGFF
`multiscales` metadata — there is no anisotropy parameter. The spatial `unit` from
that metadata drives the scale bar: metric units (OME-NGFF spells them out,
`"micrometer"`, `"nanometer"`, `"centimeter"`, …) are converted to micrometres, which
BigDataViewer then labels as nm / µm / mm / m as appropriate. Non-metric units are
passed through as-is. The bar is in units of the sample *before* expansion, i.e.
voxel size divided by `expansionFactor`.

## Scope

- Single channel (grayscale) to start.
- PNG frame-sequence output (no built-in video encoding).

## License

BSD 3-Clause. Copyright © 2026 Howard Hughes Medical Institute. See
[`LICENSE`](LICENSE).
