// One-off fixture generator for OLDER datasketches-java lines (3.3.0, 4.2.0, 5.0.2, 6.0.0, 6.1.1), which all run on
// JDK 21 without flags (research R1). Derived from gen68/Gen68.java: HLL and Theta as there, KLL through reflection
// because the KLL method names changed across these lines.
//
// Usage: java ... GenOld <outDir> <script-label> <lib-version>
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
import org.apache.datasketches.memory.Memory;
import org.apache.datasketches.theta.CompactSketch;
import org.apache.datasketches.theta.Intersection;
import org.apache.datasketches.theta.SetOperation;
import org.apache.datasketches.theta.Sketch;
import org.apache.datasketches.theta.UpdateSketch;

public class GenOld {
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
        hllExpected(HllSketch.heapify(Memory.wrap(compact))));
    write("hll", base + "_updatable", updatable, inputs, params,
        hllExpected(HllSketch.heapify(Memory.wrap(updatable))));
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
        org.apache.datasketches.hll.Union u = new org.apache.datasketches.hll.Union(12);
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
    for (String c : new String[] {"org.apache.datasketches.thetacommon.ThetaUtil", "org.apache.datasketches.common.Util", "org.apache.datasketches.Util"}) {
      try {
        return ((Short) Class.forName(c).getMethod("computeSeedHash", long.class).invoke(null, seed)) & 0xFFFF;
      } catch (ReflectiveOperationException e) {
        // try the next location
      }
    }
    throw new IllegalStateException("computeSeedHash not found in this library version");
  }

  // ---------------------------------------------------------------- Theta
  static Obj thetaExpected(Sketch s, long seed) {
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

  static UpdateSketch upd(Family f, int nominal, long seed, float p, int from, int to) {
    UpdateSketch u = UpdateSketch.builder().setFamily(f).setNominalEntries(nominal).setSeed(seed).setP(p).build();
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
        UpdateSketch u = upd(Family.QUICKSELECT, 1024, seed, 1.0f, 0, n);
        for (boolean ordered : new boolean[] {true, false}) {
          if (!ordered && n < 2) continue;
          CompactSketch c = u.compact(ordered, null);
          byte[] b = c.toByteArray();
          write("theta", "theta_compact_" + (ordered ? "ordered" : "unordered") + "_n" + n + sd, b, thetaIn(0, n),
              thetaParams("compact", 1024, seed, 1.0f), thetaExpected(Sketch.wrap(Memory.wrap(b), seed), seed));
        }
      }
      if (seed == DEFAULT_SEED) {
        for (int n : new int[] {0, 100, 5000}) {
          for (Family f : new Family[] {Family.QUICKSELECT, Family.ALPHA}) {
            UpdateSketch u = upd(f, 1024, seed, 1.0f, 0, n);
            byte[] b = u.toByteArray();
            write("theta", "theta_" + f.toString().toLowerCase() + "_n" + n, b, thetaIn(0, n),
                thetaParams(f.toString().toLowerCase() + " update sketch", 1024, seed, 1.0f),
                thetaExpected(Sketch.heapify(Memory.wrap(b), seed), seed));
          }
        }
      } else {
        UpdateSketch u = upd(Family.QUICKSELECT, 1024, seed, 1.0f, 0, 100);
        byte[] b = u.toByteArray();
        write("theta", "theta_quickselect_n100" + sd, b, thetaIn(0, 100),
            thetaParams("quickselect update sketch", 1024, seed, 1.0f),
            thetaExpected(Sketch.heapify(Memory.wrap(b), seed), seed));
      }
    }
    // Sampling probability below 1 puts theta under 1.0 even for few items.
    UpdateSketch ps = upd(Family.QUICKSELECT, 1024, DEFAULT_SEED, 0.5f, 0, 1000);
    byte[] pb = ps.compact(true, null).toByteArray();
    write("theta", "theta_compact_ordered_p05_n1000", pb, thetaIn(0, 1000), thetaParams("compact", 1024, DEFAULT_SEED, 0.5f),
        thetaExpected(Sketch.wrap(Memory.wrap(pb)), DEFAULT_SEED));
    byte[] pu = ps.toByteArray();
    write("theta", "theta_quickselect_p05_n1000", pu, thetaIn(0, 1000), thetaParams("quickselect update sketch", 1024, DEFAULT_SEED, 0.5f),
        thetaExpected(Sketch.heapify(Memory.wrap(pu)), DEFAULT_SEED));
    // A larger nominal size.
    byte[] big = upd(Family.QUICKSELECT, 4096, DEFAULT_SEED, 1.0f, 0, 100000).compact(true, null).toByteArray();
    write("theta", "theta_compact_ordered_k4096_n100000", big, thetaIn(0, 100000), thetaParams("compact", 4096, DEFAULT_SEED, 1.0f),
        thetaExpected(Sketch.wrap(Memory.wrap(big)), DEFAULT_SEED));

    // Set operations serialize their own state (families 4 and 5). AnotB has no serializer, so no fixture.
    UpdateSketch a = upd(Family.QUICKSELECT, 1024, DEFAULT_SEED, 1.0f, 0, 3000);
    UpdateSketch b = upd(Family.QUICKSELECT, 1024, DEFAULT_SEED, 1.0f, 2000, 5000);
    org.apache.datasketches.theta.Union un = SetOperation.builder().setNominalEntries(1024).buildUnion();
    un.union(a);
    un.union(b);
    write("theta", "theta_union_state_n5000", un.toByteArray(),
        new Obj().with("kind", "union of two ranges").with("ranges", List.of("0..2999", "2000..4999")),
        thetaParams("union state", 1024, DEFAULT_SEED, 1.0f), thetaExpected(un.getResult(), DEFAULT_SEED));
    Intersection in = SetOperation.builder().setNominalEntries(1024).buildIntersection();
    in.intersect(a);
    in.intersect(b);
    write("theta", "theta_intersection_state", in.toByteArray(),
        new Obj().with("kind", "intersection of two ranges").with("ranges", List.of("0..2999", "2000..4999")),
        thetaParams("intersection state", 1024, DEFAULT_SEED, 1.0f), thetaExpected(in.getResult(), DEFAULT_SEED));
    Intersection ie = SetOperation.builder().setNominalEntries(1024).buildIntersection();
    ie.intersect(upd(Family.QUICKSELECT, 1024, DEFAULT_SEED, 1.0f, 0, 10));
    ie.intersect(upd(Family.QUICKSELECT, 1024, DEFAULT_SEED, 1.0f, 100, 110));
    write("theta", "theta_intersection_state_empty_result", ie.toByteArray(),
        new Obj().with("kind", "intersection of two disjoint ranges").with("ranges", List.of("0..9", "100..109")),
        thetaParams("intersection state", 1024, DEFAULT_SEED, 1.0f), thetaExpected(ie.getResult(), DEFAULT_SEED));
  }

  // ---------------------------------------------------------------- KLL (reflection: the API differs between lines)
  static Object callAny(Class<?> base, Object o, String... names) throws Exception {
    for (String n : names) {
      try { return base.getMethod(n).invoke(o); } catch (NoSuchMethodException e) { /* try next */ }
    }
    throw new NoSuchMethodException(String.join("/", names));
  }

  static Object quantile(Class<?> base, Object o, double r) throws Exception {
    return base.getMethod("getQuantile", double.class).invoke(o, r);
  }

  static void genKll() throws IOException {
    for (String type : new String[] {"Floats", "Doubles"}) {
      Class<?> c;
      try {
        c = Class.forName("org.apache.datasketches.kll.Kll" + type + "Sketch");
      } catch (ClassNotFoundException e) {
        continue;
      }
      for (int n : new int[] {0, 1, 10000}) {
        try {
          Object s;
          try { s = c.getMethod("newHeapInstance", int.class).invoke(null, 200); }
          catch (NoSuchMethodException e) { s = c.getConstructor(int.class).newInstance(200); }
          java.lang.reflect.Method upd = c.getMethod("update", type.equals("Floats") ? float.class : double.class);
          for (int i = 1; i <= n; i++) upd.invoke(s, type.equals("Floats") ? (Object) (float) i : (Object) (i * 1.5));
          byte[] b = (byte[]) c.getMethod("toByteArray").invoke(s);
          Object r = c.getMethod("heapify", Memory.class).invoke(null, Memory.wrap(b));
          String t = type.equals("Floats") ? "float" : "double";
          Obj e = new Obj().with("n", callAny(c, r, "getN")).with("k", 200).with("itemType", t)
              .with("empty", callAny(c, r, "isEmpty")).with("retained", callAny(c, r, "getNumRetained"));
          if (n > 0) {
            Object min = callAny(c, r, "getMinItem", "getMinValue"), max = callAny(c, r, "getMaxItem", "getMaxValue");
            List<Object> qs = new ArrayList<>();
            for (double rank : RANKS) qs.add(List.of(rank, quantile(c, r, rank)));
            e.with("min", min).with("max", max).with("quantiles", qs);
          }
          write("kll", "kll_" + t + "_k200_n" + n, b, new Obj().with("kind", "values i (float) or i*1.5 (double), i=1..n").with("count", n),
              new Obj().with("k", 200).with("itemType", t), e);
        } catch (Exception ex) {
          System.err.println("skipping KLL " + type + " n=" + n + ": " + ex);
        }
      }
    }
  }

  public static void main(String[] a) throws Exception {
    out = Paths.get(a[0]);
    script = a.length > 1 ? a[1] : "fixturegen/jvm/genold";
    libVersion = a.length > 2 ? a[2] : "unknown";
    genHll();
    genTheta();
    genKll();
    System.out.println("wrote " + written + " blobs to " + out + " with datasketches-java " + libVersion + " on JDK "
        + System.getProperty("java.version"));
  }
}
