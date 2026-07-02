# Microscopy Movie Maker

A standalone, GUI-driven tool for authoring fly-through movies of large 3D
microscopy volumes stored as **N5** or **OME-Zarr** (including Zarr v3). It
turns the old copy-paste-recompile workflow (hand-editing `transforms[]` arrays
in Java) into a reproducible, config-file-driven pipeline:

```
setup → interactive keyframe capture → visual verify/reorder → JSON config → headless PNG render
```

The rendering machinery (BigDataViewer offscreen renderer, CLAHE/CLLCN
normalization, similarity-transform interpolation with acceleration curves) is
ported from the `saalfeldlab/hot-knife` movie classes, but this project has **no
dependency on hot-knife**.

## Build

```bash
mvn clean package
```

produces a runnable uber-jar at `target/microscopy-movie-maker-*.jar`.

## Usage

### 1. Author a movie (GUI)

```bash
java -jar target/microscopy-movie-maker-0.0.1-SNAPSHOT.jar
```

- **Setup**: enter the data path, output (movie) directory, expansion factor,
  CLAHE slope, and histogram clip range (min/max).
- **Navigate**: a BigDataViewer window opens. Pan / zoom / scroll to a view you
  like and press **T** to capture it as a key point. Key points are stored, in
  order, as world-point + zoom (screen-size independent).
- **Verify**: each key point gets a thumbnail. Set the number of **frames** and
  the **acceleration** curve for each segment between key points, optionally
  **hold** on the first key point, and optionally **return to the first key
  point** at the end. Drag thumbnails to reorder. Save the config and/or render.

### 2. Render from a config (headless)

```bash
java -jar target/microscopy-movie-maker-0.0.1-SNAPSHOT.jar --config movie-config.json
```

Writes `img-0000.png`, `img-0001.png`, … into the movie directory. Assemble
into a video with e.g. ffmpeg:

```bash
ffmpeg -r 30 -i img-%04d.png -c:v libx264 -pix_fmt yuv420p movie.mp4
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
    { "wx": 41517.9, "wy": 3347.7, "wz": 27096.0, "scale": 0.117432 }
  ],
  "segments": [
    { "frames": 0, "accel": 0 }
  ]
}
```

- `keyPoints[i]` is a world-point + zoom (`scale` = screen pixels per world
  unit), built via `viewCenteredOn`.
- `segments[k]` is the motion from `keyPoints[k-1]` → `keyPoints[k]`
  (`segments[0]` is unused / the arrival at the first key point).
- Acceleration types: `0` symmetric, `1` slow start, `2` slow end,
  `3` soft symmetric, `4` soft slow start, `5` soft slow end.

Per-level scale / anisotropy is read automatically from the dataset's OME-NGFF
`multiscales` metadata — there is no anisotropy parameter.

## Scope

- Single channel (grayscale) to start.
- PNG frame-sequence output (no built-in video encoding).
