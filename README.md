# xgboost-javaclient-linux64

Java 25 client library for **XGBoost**, built on the Foreign Function & Memory
(FFM) API over jextract-generated bindings for the XGBoost C API.

No JNI shim. No native payload inside the jar. Zero-copy off-heap ingest.

```xml
<dependency>
  <groupId>com.merusphere.devops.xgboost</groupId>
  <artifactId>xgboost-javaclient-linux64</artifactId>
  <version>1.0.0</version>
</dependency>
```

```gradle
implementation 'com.merusphere.devops.xgboost:xgboost-javaclient-linux64:1.0.0'
```

| | |
|---|---|
| Java | 25 (`--release 25`) |
| Platform | Linux x64 |
| XGBoost | 3.2.0 C API (`libxgboost.so` supplied at runtime) |
| Dependencies | none — JDK only |
| License | Apache 2.0 |

---

## Why not xgboost4j

XGBoost already ships an official Java package built on JNI, so this is a
deliberate replacement rather than a gap being filled:

- **No JNI shim to compile or version.** The binding layer is generated from
  `c_api.h` by jextract; there is no hand-written C to keep in step with the
  header.
- **No bundled `.so` per platform.** `libxgboost.so` is a deployment
  dependency resolved at runtime, not a payload inside a fat jar. You choose the
  build — CPU, CUDA, your own compile.
- **Zero-copy ingest.** XGBoost's `XGDMatrixCreateFrom*` entry points take an
  `__array_interface__` descriptor: a small JSON document whose `data` field is
  the *address* of a buffer. This client allocates off-heap through an `Arena`,
  hands XGBoost the address, and XGBoost reads that memory directly. No JNI
  critical sections, no copy through a `jfloatArray`.
- **A surface we control.**

---

## Quick start

```java
import com.merusphere.devops.xgboost.javaclient.linux64.*;

float[] x = ...;   // row-major, nrow * ncol
float[] y = ...;   // nrow labels

Xgb.setVerbosity(0);                       // silence XGBoost's stdout logging

try (DMatrix train = DMatrix.fromDense(x, nrow, ncol);
     Booster booster = Booster.create(train)) {

    train.setLabel(y);
    booster.setParams(Map.of(
            "objective", "binary:logistic",
            "max_depth", "4",
            "eta",       "0.3"));

    booster.train(train, 100);

    Prediction p = booster.predict(train);
    float[] probabilities = p.values();

    booster.saveModel("model.json");
}
```

Inference from a saved model:

```java
try (Booster booster = Booster.loadModel("model.json");
     DMatrix batch = DMatrix.fromDense(features, rows, cols)) {
    Prediction p = booster.predict(batch);
}
```

Sparse input:

```java
try (DMatrix m = DMatrix.fromCsr(indptr, indices, values, ncol)) { ... }
```

Margins, leaf indices and SHAP contributions come from the same call:

```java
Prediction margin = booster.predict(data, PredictType.MARGIN);
Prediction shap   = booster.predict(data, PredictType.CONTRIBUTION);
```

Round-tripping a model through memory (for a model registry, a cache, or an
inference service that ships models over the wire):

```java
byte[] bytes = booster.toBytes();               // UBJSON
try (Booster b = Booster.loadModel(bytes)) { ... }
```

---

## Runtime requirements

### 1. `libxgboost.so`

Not bundled. Install it once:

```bash
./scripts/01-install-prereqs-ubuntu.sh      # puts it under /opt/xgboost/lib
```

or lift it out of the official wheel by hand:

```bash
pip download xgboost==3.2.0 --no-deps -d /tmp/xgb
cd /tmp/xgb && unzip -o xgboost-*.whl
# xgboost/lib/libxgboost.so  +  xgboost.libs/libgomp-*.so  (the vendored OpenMP)
```

XGBoost links against **libgomp** and the C++ runtime. The wheel vendors its own
patched libgomp under `xgboost.libs/` — if you use the wheel's `.so`, that
directory has to travel with it.

The client finds the library in this order:

1. `-Dxgboost.library.path=/dir` (or a full path to the `.so`)
2. `XGBOOST_LIBRARY_PATH=/dir`
3. `System.loadLibrary("xgboost")` — `java.library.path`, `LD_LIBRARY_PATH`,
   ldconfig

If it can't be found, the failure names every path that was tried rather than
surfacing a bare `UnsatisfiedLinkError`.

### 2. Native access

JDK 25 requires native access to be granted explicitly:

```
java --enable-native-access=ALL-UNNAMED ...
```

The jar manifest carries `Enable-Native-Access: ALL-UNNAMED`, so `java -jar` is
covered; the flag is only needed for classpath launches. Under Maven, surefire
is already configured with it.

---

## API

| Type | Purpose |
|---|---|
| `Xgb` | library loading, `version()`, `buildInfo()`, global config, `setVerbosity()` |
| `DMatrix` | training/inference data — `fromDense`, `fromCsr`, `fromUri`, labels, weights, base margin, feature names |
| `Booster` | the model — `setParam`, `update`/`train`, `evaluate`, `predict`, save/load, attributes, `dumpModel` |
| `Prediction` | record of `shape` + flattened `values`, with `rows()` / `columns()` / `row(i)` |
| `PredictType` | `NORMAL`, `MARGIN`, `CONTRIBUTION`, `APPROX_CONTRIBUTION`, `INTERACTION`, `APPROX_INTERACTION`, `LEAF` |
| `XgbException` | unchecked; carries the failing C entry point and XGBoost's own message |

`DMatrix` and `Booster` are `AutoCloseable` and own native memory. Close them.
`close()` is idempotent, and using a closed handle throws `XgbException` rather
than segfaulting the JVM.

Neither class is thread-safe. XGBoost parallelises internally; drive one booster
from one thread, or give each thread its own booster loaded from the same bytes.

---

## Project layout

```
native/include/xgboost/c_api.h   vendored header, pinned to XGBoost 3.2.0
src/generated/java/              jextract output (committed) — package ...linux64.capi
src/main/java/                   the hand-written client
  .../linux64/                     Xgb, DMatrix, Booster, Prediction, PredictType, XgbException
  .../linux64/internal/            NativeLibrary, Ffm, ArrayInterface
  .../linux64/demo/                XgboostDemo — the jar's main class
src/test/java/                   JUnit 5 suite
scripts/                         build, binding generation, self-check, release
```

The generated package holds exactly two files. `c_api.h` declares 102 entry
points; the filter in `scripts/02-generate-bindings.sh` binds the ~37 the client
actually calls, which is what keeps the output at two files instead of several
hundred. Widening the surface is one added `--include-function` line.

**The generated sources are committed.** A normal build never runs jextract —
it is only needed after bumping the vendored header.

---

## Building

```bash
./scripts/01-install-prereqs-ubuntu.sh    # JDK 25, Maven, libxgboost.so, jextract
./scripts/03-build.sh                     # mvn clean package + tests
./scripts/run-demo.sh                     # train/predict against the built jar
```

Without Maven (JDK only, offline — the library has no compile dependencies):

```bash
./scripts/build-without-maven.sh
```

Regenerate the bindings after bumping `native/include/xgboost/c_api.h`:

```bash
./scripts/02-generate-bindings.sh
```

### Eclipse

The repository carries `.project` / `.classpath` with all three source folders
(`src/main/java`, `src/generated/java`, `src/test/java`) already registered.
Import as **Existing Projects into Workspace**. If you prefer m2e, import as
**Existing Maven Projects** instead and let it regenerate the metadata.

---

## Verification

Two layers, both runnable on any box with the native library.

**Self-check** — 36 assertions, no Maven, no network, no JUnit:

```bash
./scripts/selfcheck.sh
```

Covers ingest shapes, label round trip, training convergence, prediction ranges,
all four output types, CSR/dense agreement, model persistence in memory and on
disk, attribute round trip, use-after-close, and native error propagation.

**Cross-check against the reference binding** — the one that actually matters:

```bash
./scripts/selfcheck.sh /tmp/java_predictions.txt
python3 scripts/reference-run.py /tmp/java_predictions.txt
```

Both sides generate the identical dataset from a shared 64-bit LCG, train with
identical parameters against the same `libxgboost.so`, and the predictions are
diffed. Current result: **400/400 bit-identical**, max absolute difference 0.

The same coverage exists as a JUnit 5 suite for `mvn test`.

---

## Things the C API does that are worth knowing

- **Parameters are not validated at set time.** `XGBoosterSetParam` accepts an
  unknown objective without complaint; the failure surfaces at the first
  `update()`. A test pins this so it isn't mistaken for a client bug.
- **Prediction type codes are not what you would guess.** In
  `XGBoosterPredictFromDMatrix`, leaf prediction is `6` and contribution is `2`.
  The older `XGBoosterPredict` uses a different, incompatible encoding where
  leaf is `2`. `PredictType` follows the former.
- **`XGBGetLastError()` is declared with an empty parameter list**, which C
  treats as unprototyped, so jextract models it as a variadic invoker rather
  than a plain static method.
- **Error strings are thread-local** and are overwritten by the next failing
  call, so they are captured at the point of failure rather than looked up
  lazily.
- **XGBoost's JSON parser accepts bare `NaN` and `Infinity`**, which strict JSON
  forbids — and depends on it, since the default missing value is NaN.
- **Buffers returned by `predict`, `getFloatInfo`, `toBytes` and `dumpModel`
  belong to XGBoost** and are invalidated by the next call into the library.
  This client copies them before returning; callers never hold a native pointer.

---

## Not covered yet

Deliberately out of scope for 1.0.0, in rough order of effort:

- `XGDMatrixCreateFromCallback` / `XGExtMemQuantileDMatrixCreateFromCallback` —
  external-memory and streaming ingest. These need FFM **upcalls**
  (`Linker.upcallStub` with an `Arena` outliving the call).
- `QuantileDMatrix` — cheaper `hist` training on large data.
- GPU paths (`*FromCudaArrayInterface`, `*FromCudaColumnar`), which need the
  CUDA-enabled build of the shared object.
- The distributed tracker and collective communicator.
- Columnar / Arrow ingest.

---

## Releasing

See [RELEASING.md](RELEASING.md).

## License

Apache License 2.0 — see [LICENSE](LICENSE).

Copyright © 2026 [MeruSphere](https://github.com/MeruSphereOss).
