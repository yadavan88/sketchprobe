// Fixture generator for classic Quantiles (QuantilesDoublesSketch, family 8) with datasketches-java 9.0.0 (JDK 25, no flags).
// Writes <outDir>/quantiles/<name>.bin and <name>.json. Additive: it does not touch the other families.
//
// The sketch is randomized when it compacts (a random offset), so rerunning gives different blobs. Expected values in
// each manifest are read back from the written bytes with QuantilesDoublesSketch.heapify, never derived from the input.
//
// Usage: java ... GenQuantiles <outDir> <script-label> <lib-version>
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.datasketches.quantilescommon.QuantileSearchCriteria;
import org.apache.datasketches.quantiles.QuantilesDoublesSketch;
import org.apache.datasketches.quantiles.UpdatableQuantilesDoublesSketch;

public class GenQuantiles {
  static Path out;
  static String libVersion;
  static String script;
  static int written = 0;

  static class Obj extends LinkedHashMap<String, Object> {
    Obj with(String k, Object v) { put(k, v); return this; }
  }

  static String json(Object o, String ind) {
    if (o == null) return "null";
    if (o instanceof String s) return quote(s);
    if (o instanceof Double d) return (d.isNaN() || d.isInfinite()) ? "null" : d.toString();
    if (o instanceof Number || o instanceof Boolean) return o.toString();
    if (o instanceof Map<?, ?> m) {
      if (m.isEmpty()) return "{}";
      StringBuilder sb = new StringBuilder("{\n");
      int i = 0;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        sb.append(ind).append("  ").append(quote(e.getKey().toString())).append(": ")
          .append(json(e.getValue(), ind + "  ")).append(++i < m.size() ? ",\n" : "\n");
      }
      return sb.append(ind).append("}").toString();
    }
    if (o instanceof List<?> l) {
      StringBuilder sb = new StringBuilder("[");
      for (int i = 0; i < l.size(); i++) sb.append(i > 0 ? ", " : "").append(json(l.get(i), ind));
      return sb.append("]").toString();
    }
    throw new IllegalArgumentException(o.getClass().toString());
  }

  static String quote(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      if (c == '"' || c == '\\') sb.append('\\').append(c);
      else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
      else sb.append(c);
    }
    return sb.append('"').toString();
  }

  static String hex(byte[] b, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < Math.min(n, b.length); i++) sb.append(String.format("%02x", b[i] & 0xFF));
    return sb.toString();
  }

  static Obj header(byte[] b) {
    return new Obj().with("lengthBytes", b.length).with("byte0", b[0] & 0xFF).with("serVer", b[1] & 0xFF)
        .with("familyId", b[2] & 0xFF).with("firstBytesHex", hex(b, 24));
  }

  static void write(String name, byte[] bytes, Obj inputs, Obj params, Obj expected) throws IOException {
    Path dir = out.resolve("quantiles");
    Files.createDirectories(dir);
    Files.write(dir.resolve(name + ".bin"), bytes);
    Obj gen = new Obj().with("library", "datasketches-java").with("version", libVersion)
        .with("jdk", System.getProperty("java.version")).with("script", script);
    Obj m = new Obj().with("name", name).with("family", "quantiles").with("generator", gen)
        .with("inputs", inputs).with("params", params).with("header", header(bytes)).with("expected", expected);
    Files.writeString(dir.resolve(name + ".json"), json(m, "") + "\n");
    written++;
  }

  static final double[] RANKS = {0.0, 0.01, 0.05, 0.25, 0.5, 0.75, 0.95, 0.99, 1.0};

  /** What the library reports for these exact bytes. */
  static Obj expected(byte[] b) {
    QuantilesDoublesSketch s = QuantilesDoublesSketch.heapify(MemorySegment.ofArray(b));
    Obj e = new Obj().with("n", s.getN()).with("k", s.getK()).with("empty", s.isEmpty())
        .with("retained", s.getNumRetained());
    if (!s.isEmpty()) {
      e.with("min", s.getMinItem()).with("max", s.getMaxItem());
      List<Object> qs = new ArrayList<>();
      for (double r : RANKS) qs.add(Arrays.<Object>asList(r, s.getQuantile(r, QuantileSearchCriteria.INCLUSIVE)));
      e.with("quantiles", qs);
    }
    return e;
  }

  static void emit(String shape, int k, long n, double step, Obj inputs) throws IOException {
    UpdatableQuantilesDoublesSketch s = QuantilesDoublesSketch.builder().setK(k).build();
    for (long i = 1; i <= n; i++) s.update(i * step);
    for (boolean compact : new boolean[] {true, false}) {
      byte[] b = s.toByteArray(compact);
      write("quantiles_k" + k + "_" + shape + (compact ? "_compact" : "_updatable"), b, inputs,
          new Obj().with("k", k).with("itemType", "double").with("layout", compact ? "compact" : "updatable"),
          expected(b));
      System.out.println("k" + k + " " + shape + (compact ? " compact " : " updatable ") + b.length + " bytes, header "
          + hex(b, 8));
    }
  }

  public static void main(String[] a) throws Exception {
    out = Paths.get(a[0]);
    script = a.length > 1 ? a[1] : "fixturegen/jvm/genquantiles";
    libVersion = a.length > 2 ? a[2] : "unknown";
    for (int k : new int[] {128, 32}) {
      emit("empty", k, 0, 1.5, new Obj().with("kind", "nothing was added").with("count", 0));
      emit("n1", k, 1, 1.5, new Obj().with("kind", "one value, 1.5").with("count", 1));
      emit("n10", k, 10, 1.5, new Obj().with("kind", "values i*1.5, i=1..n").with("count", 10));
      emit("n" + (2 * k - 1), k, 2 * k - 1, 1.5, new Obj().with("kind", "values i*1.5, i=1..n, just below 2k").with("count", 2 * k - 1));
      emit("n" + (2 * k), k, 2 * k, 1.5, new Obj().with("kind", "values i*1.5, i=1..n, exactly 2k").with("count", 2 * k));
      emit("n10000", k, 10000, 1.5, new Obj().with("kind", "values i*1.5, i=1..n").with("count", 10000));
      emit("n200000", k, 200000, 1.5, new Obj().with("kind", "values i*1.5, i=1..n").with("count", 200000));
    }
    System.out.println("wrote " + written + " blobs to " + out + " with datasketches-java " + libVersion + " on JDK "
        + System.getProperty("java.version"));
  }
}
