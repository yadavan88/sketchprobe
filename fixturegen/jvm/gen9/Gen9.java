// Fixture generator for datasketches-java 9.0.0 (JDK 25, no flags).
// Derived from gen68/Gen68.java. 9.0.0 renamed the Theta classes and replaced Memory with
// java.lang.foreign.MemorySegment, and it can write the compressed Theta format (serVer 4).
// Adds: compressed compact Theta sketches, and KLL sketches in the updatable layout.
//
// Usage: java ... Gen9 <outDir> <script-label> <lib-version>
// Writes <outDir>/{hll,theta,kll}/<name>.bin and <name>.json.
//
// Expected values in each manifest come from the real library, read back from the written bytes
// where the library allows it. They are never derived from the input data: KLL compaction is
// randomized, so the same input gives a different blob on each run (research R11).
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.datasketches.common.Family;
import org.apache.datasketches.hll.HllSketch;
import org.apache.datasketches.hll.TgtHllType;
import org.apache.datasketches.kll.KllDoublesSketch;
import org.apache.datasketches.kll.KllFloatsSketch;
import org.apache.datasketches.kll.KllLongsSketch;
import java.lang.foreign.MemorySegment;
import org.apache.datasketches.theta.CompactThetaSketch;
import org.apache.datasketches.theta.ThetaIntersection;
import org.apache.datasketches.theta.ThetaSetOperation;
import org.apache.datasketches.theta.ThetaSketch;
import org.apache.datasketches.theta.ThetaUnion;
import org.apache.datasketches.theta.UpdatableThetaSketch;

public class Gen9 {
  static final double[] RANKS = {0, 0.01, 0.05, 0.25, 0.5, 0.75, 0.95, 0.99, 1};
  static final long DEFAULT_SEED = 9001L;

  static Path out;
  static String libVersion;
  static String script;
  static int written = 0;

  // ---- tiny JSON model: no dependencies, so the generator stays a single file ----
  static class Obj extends LinkedHashMap<String, Object> {
    Obj with(String k, Object v) { put(k, v); return this; }
  }

  static String json(Object o, String ind) {
    if (o == null) return "null";
    if (o instanceof String s) return quote(s);
    if (o instanceof Double d) return (d.isNaN() || d.isInfinite()) ? "null" : d.toString();
    if (o instanceof Float f) return (f.isNaN() || f.isInfinite()) ? "null" : Double.toString(f.doubleValue());
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

  /** Header facts read from the written bytes (informational; the library does not expose them). */
  static Obj header(byte[] b) {
    return new Obj().with("lengthBytes", b.length).with("byte0", b[0] & 0xFF).with("serVer", b[1] & 0xFF)
        .with("familyId", b[2] & 0xFF).with("firstBytesHex", hex(b, 24));
  }

  static void write(String family, String name, byte[] bytes, Obj inputs, Obj params, Obj expected) throws IOException {
    Path dir = out.resolve(family);
    Files.createDirectories(dir);
    Files.write(dir.resolve(name + ".bin"), bytes);
    Obj gen = new Obj().with("library", "datasketches-java").with("version", libVersion)
        .with("jdk", System.getProperty("java.version")).with("script", script);
    Obj m = new Obj().with("name", name).with("family", family).with("generator", gen)
        .with("inputs", inputs).with("params", params).with("header", header(bytes)).with("expected", expected);
    Files.writeString(dir.resolve(name + ".json"), json(m, "") + "\n");
    written++;
  }

  // ---------------------------------------------------------------- HLL
  static Obj hllExpected(HllSketch h) {
    Obj e = new Obj().with("estimate", h.getEstimate()).with("lgK", h.getLgConfigK())
        .with("targetType", h.getTgtHllType().toString()).with("empty", h.isEmpty())
        .with("compact", h.isCompact());
    for (int sd = 1; sd <= 3; sd++) {
      e.put("lowerBound" + sd, h.getLowerBound(sd));
      e.put("upperBound" + sd, h.getUpperBound(sd));
    }
    return e;
  }

  static void hllBoth(String base, HllSketch h, Obj inputs, Obj params) throws IOException {
    byte[] compact = h.toCompactByteArray();
    byte[] updatable = h.toUpdatableByteArray();
    write("hll", base + "_compact", compact, inputs, params,
        hllExpected(HllSketch.heapify(MemorySegment.ofArray(compact))));
    write("hll", base + "_updatable", updatable, inputs, params,
        hllExpected(HllSketch.heapify(MemorySegment.ofArray(updatable))));
  }

  static HllSketch hll(int lgK, TgtHllType t, int from, int to) {
    HllSketch h = new HllSketch(lgK, t);
    for (int i = from; i < to; i++) h.update(i);
    return h;
  }

  static void genHll() throws IOException {
    int[][] cases = {{8, 20}, {8, 300}, {12, 1}, {12, 5}, {12, 50}, {12, 1000}, {12, 100000}, {16, 100000}};
    for (TgtHllType t : TgtHllType.values()) {
      for (int[] c : cases) {
        int lgK = c[0], n = c[1];
        hllBoth("hll_" + t + "_k" + lgK + "_n" + n, hll(lgK, t, 0, n),
            new Obj().with("kind", "distinct longs").with("range", "0.." + (n - 1)).with("count", n),
            new Obj().with("lgK", lgK).with("targetType", t.toString()));
      }
      hllBoth("hll_" + t + "_k12_empty", new HllSketch(12, t), new Obj().with("kind", "none").with("count", 0),
          new Obj().with("lgK", 12).with("targetType", t.toString()));
      // Merged sketches are marked out-of-order and need the composite estimator.
      for (int[][] r : new int[][][] {{{0, 1000}, {500, 2000}}, {{0, 100000}, {50000, 150000}}}) {
        org.apache.datasketches.hll.HllUnion u = new org.apache.datasketches.hll.HllUnion(12);
        u.update(hll(12, t, r[0][0], r[0][1]));
        u.update(hll(12, t, r[1][0], r[1][1]));
        HllSketch merged = u.getResult(t);
        hllBoth("hll_" + t + "_k12_union_" + r[0][1] + "_" + r[1][1], merged,
            new Obj().with("kind", "union of two distinct-long ranges")
                .with("ranges", List.of(r[0][0] + ".." + (r[0][1] - 1), r[1][0] + ".." + (r[1][1] - 1))),
            new Obj().with("lgK", 12).with("targetType", t.toString()).with("outOfOrderExpected", true));
      }
    }
  }

  /** The seed hash helper moved between library lines, so look it up at runtime. */
  static int seedHash(long seed) {
    for (String c : new String[] {"org.apache.datasketches.thetacommon.ThetaUtil", "org.apache.datasketches.common.Util"}) {
      try {
        return ((Short) Class.forName(c).getMethod("computeSeedHash", long.class).invoke(null, seed)) & 0xFFFF;
      } catch (ReflectiveOperationException e) {
        // try the next location
      }
    }
    throw new IllegalStateException("computeSeedHash not found in this library version");
  }

  // ---------------------------------------------------------------- Theta
  static Obj thetaExpected(ThetaSketch s, long seed) {
    Obj e = new Obj().with("estimate", s.getEstimate()).with("theta", s.getTheta())
        .with("retainedValid", s.getRetainedEntries(true)).with("retainedAll", s.getRetainedEntries(false))
        .with("empty", s.isEmpty()).with("ordered", s.isOrdered()).with("compact", s.isCompact())
        .with("estimationMode", s.isEstimationMode())
        .with("seed", Long.toString(seed)).with("seedHash", seedHash(seed));
    for (int sd = 1; sd <= 3; sd++) {
      e.put("lowerBound" + sd, s.getLowerBound(sd));
      e.put("upperBound" + sd, s.getUpperBound(sd));
    }
    return e;
  }

  static UpdatableThetaSketch upd(Family f, int nominal, long seed, float p, int from, int to) {
    UpdatableThetaSketch u = UpdatableThetaSketch.builder().setFamily(f).setNominalEntries(nominal).setSeed(seed).setP(p).build();
    for (int i = from; i < to; i++) u.update(i);
    return u;
  }

  static Obj thetaIn(int from, int to) {
    return new Obj().with("kind", "distinct longs").with("range", from + ".." + (to - 1)).with("count", to - from);
  }

  static Obj thetaParams(String shape, int nominal, long seed, float p) {
    return new Obj().with("shape", shape).with("nominalEntries", nominal).with("seed", Long.toString(seed)).with("p", (double) p);
  }

  static void genTheta() throws IOException {
    long[] seeds = {DEFAULT_SEED, 12345L};
    for (long seed : seeds) {
      String sd = seed == DEFAULT_SEED ? "" : "_seed" + seed;
      for (int n : new int[] {0, 1, 100, 5000}) {
        UpdatableThetaSketch u = upd(Family.QUICKSELECT, 1024, seed, 1.0f, 0, n);
        for (boolean ordered : new boolean[] {true, false}) {
          if (!ordered && n < 2) continue;
          CompactThetaSketch c = u.compact(ordered, null);
          byte[] b = c.toByteArray();
          write("theta", "theta_compact_" + (ordered ? "ordered" : "unordered") + "_n" + n + sd, b, thetaIn(0, n),
              thetaParams("compact", 1024, seed, 1.0f), thetaExpected(ThetaSketch.wrap(MemorySegment.ofArray(b), seed), seed));
        }
      }
      if (seed == DEFAULT_SEED) {
        for (int n : new int[] {0, 100, 5000}) {
          for (Family f : new Family[] {Family.QUICKSELECT, Family.ALPHA}) {
            UpdatableThetaSketch u = upd(f, 1024, seed, 1.0f, 0, n);
            byte[] b = u.toByteArray();
            write("theta", "theta_" + f.toString().toLowerCase() + "_n" + n, b, thetaIn(0, n),
                thetaParams(f.toString().toLowerCase() + " update sketch", 1024, seed, 1.0f),
                thetaExpected(ThetaSketch.heapify(MemorySegment.ofArray(b), seed), seed));
          }
        }
      } else {
        UpdatableThetaSketch u = upd(Family.QUICKSELECT, 1024, seed, 1.0f, 0, 100);
        byte[] b = u.toByteArray();
        write("theta", "theta_quickselect_n100" + sd, b, thetaIn(0, 100),
            thetaParams("quickselect update sketch", 1024, seed, 1.0f),
            thetaExpected(ThetaSketch.heapify(MemorySegment.ofArray(b), seed), seed));
      }
    }
    // Sampling probability below 1 puts theta under 1.0 even for few items.
    UpdatableThetaSketch ps = upd(Family.QUICKSELECT, 1024, DEFAULT_SEED, 0.5f, 0, 1000);
    byte[] pb = ps.compact(true, null).toByteArray();
    write("theta", "theta_compact_ordered_p05_n1000", pb, thetaIn(0, 1000), thetaParams("compact", 1024, DEFAULT_SEED, 0.5f),
        thetaExpected(ThetaSketch.wrap(MemorySegment.ofArray(pb)), DEFAULT_SEED));
    byte[] pu = ps.toByteArray();
    write("theta", "theta_quickselect_p05_n1000", pu, thetaIn(0, 1000), thetaParams("quickselect update sketch", 1024, DEFAULT_SEED, 0.5f),
        thetaExpected(ThetaSketch.heapify(MemorySegment.ofArray(pu)), DEFAULT_SEED));
    // A larger nominal size.
    byte[] big = upd(Family.QUICKSELECT, 4096, DEFAULT_SEED, 1.0f, 0, 100000).compact(true, null).toByteArray();
    write("theta", "theta_compact_ordered_k4096_n100000", big, thetaIn(0, 100000), thetaParams("compact", 4096, DEFAULT_SEED, 1.0f),
        thetaExpected(ThetaSketch.wrap(MemorySegment.ofArray(big)), DEFAULT_SEED));

    // Set operations serialize their own state (families 4 and 5). AnotB has no serializer, so no fixture.
    UpdatableThetaSketch a = upd(Family.QUICKSELECT, 1024, DEFAULT_SEED, 1.0f, 0, 3000);
    UpdatableThetaSketch b = upd(Family.QUICKSELECT, 1024, DEFAULT_SEED, 1.0f, 2000, 5000);
    ThetaUnion un = ThetaSetOperation.builder().setNominalEntries(1024).buildUnion();
    un.union(a);
    un.union(b);
    write("theta", "theta_union_state_n5000", un.toByteArray(),
        new Obj().with("kind", "union of two ranges").with("ranges", List.of("0..2999", "2000..4999")),
        thetaParams("union state", 1024, DEFAULT_SEED, 1.0f), thetaExpected(un.getResult(), DEFAULT_SEED));
    ThetaIntersection in = ThetaSetOperation.builder().setNominalEntries(1024).buildIntersection();
    in.intersect(a);
    in.intersect(b);
    write("theta", "theta_intersection_state", in.toByteArray(),
        new Obj().with("kind", "intersection of two ranges").with("ranges", List.of("0..2999", "2000..4999")),
        thetaParams("intersection state", 1024, DEFAULT_SEED, 1.0f), thetaExpected(in.getResult(), DEFAULT_SEED));
    ThetaIntersection ie = ThetaSetOperation.builder().setNominalEntries(1024).buildIntersection();
    ie.intersect(upd(Family.QUICKSELECT, 1024, DEFAULT_SEED, 1.0f, 0, 10));
    ie.intersect(upd(Family.QUICKSELECT, 1024, DEFAULT_SEED, 1.0f, 100, 110));
    write("theta", "theta_intersection_state_empty_result", ie.toByteArray(),
        new Obj().with("kind", "intersection of two disjoint ranges").with("ranges", List.of("0..9", "100..109")),
        thetaParams("intersection state", 1024, DEFAULT_SEED, 1.0f), thetaExpected(ie.getResult(), DEFAULT_SEED));
  }

  /** Compressed compact format (serVer 4). Only written for ordered sketches that are not empty or single-item. */
  static void genThetaCompressed() throws IOException {
    for (int n : new int[] {2, 100, 1000, 5000, 100000}) {
      int nominal = n >= 100000 ? 4096 : 1024;
      UpdatableThetaSketch u = upd(Family.QUICKSELECT, nominal, DEFAULT_SEED, 1.0f, 0, n);
      byte[] b = u.compact(true, null).toByteArrayCompressed();
      write("theta", "theta_compressed_ordered_n" + n, b, thetaIn(0, n), thetaParams("compact compressed", nominal, DEFAULT_SEED, 1.0f),
          thetaExpected(ThetaSketch.wrap(MemorySegment.ofArray(b)), DEFAULT_SEED));
    }
    UpdatableThetaSketch ps = upd(Family.QUICKSELECT, 1024, DEFAULT_SEED, 0.5f, 0, 1000);
    byte[] pb = ps.compact(true, null).toByteArrayCompressed();
    write("theta", "theta_compressed_ordered_p05_n1000", pb, thetaIn(0, 1000), thetaParams("compact compressed", 1024, DEFAULT_SEED, 0.5f),
        thetaExpected(ThetaSketch.wrap(MemorySegment.ofArray(pb)), DEFAULT_SEED));
    long seed = 12345L;
    byte[] sb = upd(Family.QUICKSELECT, 1024, seed, 1.0f, 0, 5000).compact(true, null).toByteArrayCompressed();
    write("theta", "theta_compressed_ordered_seed12345_n5000", sb, thetaIn(0, 5000), thetaParams("compact compressed", 1024, seed, 1.0f),
        thetaExpected(ThetaSketch.wrap(MemorySegment.ofArray(sb), seed), seed));
  }

  // ---------------------------------------------------------------- KLL
  static List<Object> quantiles(java.util.function.DoubleFunction<Object> q) {
    List<Object> l = new ArrayList<>();
    for (double r : RANKS) l.add(List.of(r, q.apply(r)));
    return l;
  }

  /** The updatable layout is what a sketch built in caller memory looks like. getSerializedSizeBytes() reports the
   *  compact size, so find the real length: the shortest prefix that `heapify` (a full copy, so it needs the whole sketch) can read, including an empty sketch, which `wrap` accepts when truncated. */
  static byte[] shortestWrappable(MemorySegment seg, java.util.function.ToLongFunction<MemorySegment> nOf, long wantN) {
    byte[] all = seg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
    for (int len = 8; len <= all.length; len++) {
      try {
        byte[] prefix = java.util.Arrays.copyOf(all, len);
        if (nOf.applyAsLong(MemorySegment.ofArray(prefix)) == wantN) return prefix;
      } catch (RuntimeException e) {
        // too short, keep growing
      }
    }
    throw new IllegalStateException("no wrappable prefix found");
  }

  static void updatableKll(String type, int k, int n, Obj in) throws IOException {
    MemorySegment seg = MemorySegment.ofArray(new byte[1 << 20]);
    Obj params = new Obj().with("k", k).with("itemType", type).with("layout", "updatable");
    String name = "kll_" + type + "_k" + k + "_n" + n + "_updatable";
    switch (type) {
      case "float" -> {
        KllFloatsSketch d = KllFloatsSketch.newDirectInstance(k, seg, null);
        for (int i = 1; i <= n; i++) d.update((float) i);
        byte[] b = shortestWrappable(seg, s -> { KllFloatsSketch w = KllFloatsSketch.heapify(s); if (w.getN() > 0) { w.getMinItem(); w.getMaxItem(); w.getQuantile(0.5); } return w.getN(); }, n);
        KllFloatsSketch r = KllFloatsSketch.wrap(MemorySegment.ofArray(b));
        Obj e = new Obj().with("n", r.getN()).with("k", k).with("itemType", type).with("empty", r.isEmpty()).with("retained", r.getNumRetained());
        if (n > 0) e.with("min", r.getMinItem()).with("max", r.getMaxItem()).with("quantiles", quantiles(x -> r.getQuantile(x)));
        write("kll", name, b, in, params, e);
      }
      case "double" -> {
        KllDoublesSketch d = KllDoublesSketch.newDirectInstance(k, seg, null);
        for (int i = 1; i <= n; i++) d.update(i * 1.5);
        byte[] b = shortestWrappable(seg, s -> { KllDoublesSketch w = KllDoublesSketch.heapify(s); if (w.getN() > 0) { w.getMinItem(); w.getMaxItem(); w.getQuantile(0.5); } return w.getN(); }, n);
        KllDoublesSketch r = KllDoublesSketch.wrap(MemorySegment.ofArray(b));
        Obj e = new Obj().with("n", r.getN()).with("k", k).with("itemType", type).with("empty", r.isEmpty()).with("retained", r.getNumRetained());
        if (n > 0) e.with("min", r.getMinItem()).with("max", r.getMaxItem()).with("quantiles", quantiles(x -> r.getQuantile(x)));
        write("kll", name, b, in, params, e);
      }
      default -> {
        KllLongsSketch d = KllLongsSketch.newDirectInstance(k, seg, null);
        for (int i = 1; i <= n; i++) d.update(i * 1_000_003L);
        byte[] b = shortestWrappable(seg, s -> { KllLongsSketch w = KllLongsSketch.heapify(s); if (w.getN() > 0) { w.getMinItem(); w.getMaxItem(); w.getQuantile(0.5); } return w.getN(); }, n);
        KllLongsSketch r = KllLongsSketch.wrap(MemorySegment.ofArray(b));
        Obj e = new Obj().with("n", r.getN()).with("k", k).with("itemType", type).with("empty", r.isEmpty()).with("retained", r.getNumRetained());
        if (n > 0) e.with("min", Long.toString(r.getMinItem())).with("max", Long.toString(r.getMaxItem()))
            .with("quantiles", quantiles(x -> Long.toString(r.getQuantile(x))));
        write("kll", name, b, in, params, e);
      }
    }
  }

  static void genKll() throws IOException {
    for (int k : new int[] {200, 100}) {
      for (int n : new int[] {0, 1, 10, 500, 10000, 200000}) {
        if (k == 100 && n != 10000) continue;
        Obj in = new Obj().with("kind", "values i*1.5 (float, double), i (long), i=1..n").with("count", n);
        { // float
          KllFloatsSketch s = KllFloatsSketch.newHeapInstance(k);
          for (int i = 1; i <= n; i++) s.update((float) i);
          byte[] b = s.toByteArray();
          KllFloatsSketch r = KllFloatsSketch.heapify(MemorySegment.ofArray(b));
          Obj e = new Obj().with("n", r.getN()).with("k", k).with("itemType", "float").with("empty", r.isEmpty())
              .with("retained", r.getNumRetained());
          if (n > 0) e.with("min", r.getMinItem()).with("max", r.getMaxItem()).with("quantiles", quantiles(x -> r.getQuantile(x)));
          write("kll", "kll_float_k" + k + "_n" + n, b, in, new Obj().with("k", k).with("itemType", "float"), e);
          updatableKll("float", k, n, in);
        }
        { // double
          KllDoublesSketch s = KllDoublesSketch.newHeapInstance(k);
          for (int i = 1; i <= n; i++) s.update(i * 1.5);
          byte[] b = s.toByteArray();
          KllDoublesSketch r = KllDoublesSketch.heapify(MemorySegment.ofArray(b));
          Obj e = new Obj().with("n", r.getN()).with("k", k).with("itemType", "double").with("empty", r.isEmpty())
              .with("retained", r.getNumRetained());
          if (n > 0) e.with("min", r.getMinItem()).with("max", r.getMaxItem()).with("quantiles", quantiles(x -> r.getQuantile(x)));
          write("kll", "kll_double_k" + k + "_n" + n, b, in, new Obj().with("k", k).with("itemType", "double"), e);
          updatableKll("double", k, n, in);
        }
        { // long (64-bit values are written as decimal strings in the manifest)
          KllLongsSketch s = KllLongsSketch.newHeapInstance(k);
          for (int i = 1; i <= n; i++) s.update(i * 1_000_003L);
          byte[] b = s.toByteArray();
          KllLongsSketch r = KllLongsSketch.heapify(MemorySegment.ofArray(b));
          Obj e = new Obj().with("n", r.getN()).with("k", k).with("itemType", "long").with("empty", r.isEmpty())
              .with("retained", r.getNumRetained());
          if (n > 0) e.with("min", Long.toString(r.getMinItem())).with("max", Long.toString(r.getMaxItem()))
              .with("quantiles", quantiles(x -> Long.toString(r.getQuantile(x))));
          write("kll", "kll_long_k" + k + "_n" + n, b, in, new Obj().with("k", k).with("itemType", "long"), e);
          updatableKll("long", k, n, in);
        }
      }
    }
  }

  public static void main(String[] a) throws Exception {
    out = Paths.get(a[0]);
    script = a.length > 1 ? a[1] : "fixturegen/jvm/gen9";
    libVersion = a.length > 2 ? a[2] : "unknown";
    genHll();
    genTheta();
    genThetaCompressed();
    genKll();
    System.out.println("wrote " + written + " blobs to " + out + " with datasketches-java " + libVersion + " on JDK "
        + System.getProperty("java.version"));
  }
}
