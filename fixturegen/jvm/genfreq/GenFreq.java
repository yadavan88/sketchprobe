// Fixture generator for Frequency sketches (family 10) with datasketches-java 9.0.0 (JDK 25, no flags).
// Writes <outDir>/frequency/<name>.bin and <name>.json. Additive: it does not touch the HLL, Theta or KLL blobs.
//
// Unlike KLL, a Frequency sketch is deterministic: the same stream always gives the same bytes, so rerunning this
// replaces nothing with different values. Expected values in each manifest are read back from the written bytes
// with the library (FrequentLongsSketch.getInstance / FrequentItemsSketch.getInstance), never derived from the input.
// The manifest lists every active row (item, estimate, lower bound, upper bound), largest estimate first.
//
// Usage: java ... GenFreq <outDir> <script-label> <lib-version>
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
import java.util.Random;

import org.apache.datasketches.common.ArrayOfLongsSerDe;
import org.apache.datasketches.common.ArrayOfStringsSerDe;
import org.apache.datasketches.frequencies.ErrorType;
import org.apache.datasketches.frequencies.FrequentItemsSketch;
import org.apache.datasketches.frequencies.FrequentLongsSketch;

public class GenFreq {
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


  // ---------------------------------------------------------------- Frequency
  static final String LONG = "long";
  static final String STRING = "string";

  static Obj expectedLongs(byte[] b) {
    FrequentLongsSketch s = FrequentLongsSketch.getInstance(MemorySegment.ofArray(b));
    List<Object> rows = new ArrayList<>();
    for (FrequentLongsSketch.Row r : s.getFrequentItems(0L, ErrorType.NO_FALSE_NEGATIVES))
      rows.add(Arrays.<Object>asList(r.getItem(), r.getEstimate(), r.getLowerBound(), r.getUpperBound()));
    return new Obj().with("streamLength", s.getStreamLength()).with("numActive", s.getNumActiveItems())
        .with("maximumError", s.getMaximumError()).with("currentMapCapacity", s.getCurrentMapCapacity())
        .with("maximumMapCapacity", s.getMaximumMapCapacity()).with("empty", s.isEmpty()).with("rows", rows);
  }

  static Obj expectedLongItems(byte[] b) {
    FrequentItemsSketch<Long> s = FrequentItemsSketch.getInstance(MemorySegment.ofArray(b), new ArrayOfLongsSerDe());
    List<Object> rows = new ArrayList<>();
    for (FrequentItemsSketch.Row<Long> r : s.getFrequentItems(0L, ErrorType.NO_FALSE_NEGATIVES))
      rows.add(Arrays.<Object>asList(r.getItem(), r.getEstimate(), r.getLowerBound(), r.getUpperBound()));
    return new Obj().with("streamLength", s.getStreamLength()).with("numActive", s.getNumActiveItems())
        .with("maximumError", s.getMaximumError()).with("currentMapCapacity", s.getCurrentMapCapacity())
        .with("maximumMapCapacity", s.getMaximumMapCapacity()).with("empty", s.isEmpty()).with("rows", rows);
  }

  static Obj expectedStrings(byte[] b) {
    FrequentItemsSketch<String> s = FrequentItemsSketch.getInstance(MemorySegment.ofArray(b), new ArrayOfStringsSerDe());
    List<Object> rows = new ArrayList<>();
    for (FrequentItemsSketch.Row<String> r : s.getFrequentItems(0L, ErrorType.NO_FALSE_NEGATIVES))
      rows.add(Arrays.<Object>asList(r.getItem(), r.getEstimate(), r.getLowerBound(), r.getUpperBound()));
    return new Obj().with("streamLength", s.getStreamLength()).with("numActive", s.getNumActiveItems())
        .with("maximumError", s.getMaximumError()).with("currentMapCapacity", s.getCurrentMapCapacity())
        .with("maximumMapCapacity", s.getMaximumMapCapacity()).with("empty", s.isEmpty()).with("rows", rows);
  }

  static Obj params(String itemType, int maxMapSize, String writer) {
    return new Obj().with("itemType", itemType).with("maxMapSize", maxMapSize).with("writer", writer);
  }

  /** A skewed, deterministic stream: item = floor(distinct * u^3) for u from java.util.Random(seed), which the
    * JDK specifies exactly, so the stream is the same on every JVM. */
  static long[] skewed(int events, int distinct, long seed) {
    Random r = new Random(seed);
    long[] items = new long[events];
    for (int i = 0; i < events; i++) { double u = r.nextDouble(); items[i] = (long) Math.floor(distinct * u * u * u); }
    return items;
  }

  /** Long and string versions of one stream shape, and the same stream through ItemsSketch<Long> for comparison. */
  static void shape(String shape, int m, long[] stream, Obj inputs) throws IOException {
    FrequentLongsSketch l = new FrequentLongsSketch(m);
    FrequentItemsSketch<Long> il = new FrequentItemsSketch<>(m);
    FrequentItemsSketch<String> st = new FrequentItemsSketch<>(m);
    for (long x : stream) { l.update(x); il.update(x); st.update("item-" + x); }
    write("frequency", "freq_long_m" + m + "_" + shape, l.toByteArray(), inputs, params(LONG, m, "FrequentLongsSketch"),
        expectedLongs(l.toByteArray()));
    write("frequency", "freq_itemslong_m" + m + "_" + shape, il.toByteArray(new ArrayOfLongsSerDe()), inputs,
        params(LONG, m, "FrequentItemsSketch<Long> with ArrayOfLongsSerDe"),
        expectedLongItems(il.toByteArray(new ArrayOfLongsSerDe())));
    write("frequency", "freq_string_m" + m + "_" + shape, st.toByteArray(new ArrayOfStringsSerDe()), inputs,
        params(STRING, m, "FrequentItemsSketch<String> with ArrayOfStringsSerDe"),
        expectedStrings(st.toByteArray(new ArrayOfStringsSerDe())));
    boolean same = Arrays.equals(l.toByteArray(), il.toByteArray(new ArrayOfLongsSerDe()));
    System.out.println("m" + m + " " + shape + ": FrequentLongsSketch bytes " + (same ? "EQUAL" : "DIFFERENT")
        + " to ItemsSketch<Long> bytes (" + l.toByteArray().length + " vs " + il.toByteArray(new ArrayOfLongsSerDe()).length + " bytes)");
  }

  static void genFrequency() throws IOException {
    for (int m : new int[] {8, 64}) {
      FrequentLongsSketch l = new FrequentLongsSketch(m);
      FrequentItemsSketch<Long> il = new FrequentItemsSketch<>(m);
      FrequentItemsSketch<String> st = new FrequentItemsSketch<>(m);
      Obj in = new Obj().with("kind", "nothing was added").with("count", 0);
      write("frequency", "freq_long_m" + m + "_empty", l.toByteArray(), in, params(LONG, m, "FrequentLongsSketch"), expectedLongs(l.toByteArray()));
      write("frequency", "freq_itemslong_m" + m + "_empty", il.toByteArray(new ArrayOfLongsSerDe()), in,
          params(LONG, m, "FrequentItemsSketch<Long> with ArrayOfLongsSerDe"), expectedLongItems(il.toByteArray(new ArrayOfLongsSerDe())));
      write("frequency", "freq_string_m" + m + "_empty", st.toByteArray(new ArrayOfStringsSerDe()), in,
          params(STRING, m, "FrequentItemsSketch<String> with ArrayOfStringsSerDe"), expectedStrings(st.toByteArray(new ArrayOfStringsSerDe())));
      System.out.println("m" + m + " empty: Longs " + l.toByteArray().length + " bytes, ItemsSketch<Long> "
          + il.toByteArray(new ArrayOfLongsSerDe()).length + " bytes, ItemsSketch<String> " + st.toByteArray(new ArrayOfStringsSerDe()).length + " bytes");
    }
    // one item
    {
      FrequentLongsSketch l = new FrequentLongsSketch(8);
      FrequentItemsSketch<Long> il = new FrequentItemsSketch<>(8);
      FrequentItemsSketch<String> st = new FrequentItemsSketch<>(8);
      l.update(42L); il.update(42L); st.update("answer");
      Obj in = new Obj().with("kind", "one item, once").with("count", 1);
      write("frequency", "freq_long_m8_n1", l.toByteArray(), in, params(LONG, 8, "FrequentLongsSketch"), expectedLongs(l.toByteArray()));
      write("frequency", "freq_itemslong_m8_n1", il.toByteArray(new ArrayOfLongsSerDe()), in,
          params(LONG, 8, "FrequentItemsSketch<Long> with ArrayOfLongsSerDe"), expectedLongItems(il.toByteArray(new ArrayOfLongsSerDe())));
      write("frequency", "freq_string_m8_n1", st.toByteArray(new ArrayOfStringsSerDe()), in,
          params(STRING, 8, "FrequentItemsSketch<String> with ArrayOfStringsSerDe"), expectedStrings(st.toByteArray(new ArrayOfStringsSerDe())));
    }
    // a few distinct items with weights, including a negative and a large long, and strings with non-ASCII text,
    // an empty string and a 300-character string
    {
      long[] ids = {1L, 2L, 3L, 4L, 5L, -7L, 1L << 40};
      long[] counts = {1000L, 100L, 10L, 2L, 1L, 3L, 9L};
      String[] names = {"alpha", "naïve-日本語-✓", "", "x".repeat(300), "ios-26.4.1", "Linux armv8l", "iPhone"};
      FrequentLongsSketch l = new FrequentLongsSketch(64);
      FrequentItemsSketch<Long> il = new FrequentItemsSketch<>(64);
      FrequentItemsSketch<String> st = new FrequentItemsSketch<>(64);
      for (int i = 0; i < ids.length; i++) { l.update(ids[i], counts[i]); il.update(ids[i], counts[i]); st.update(names[i], counts[i]); }
      Obj in = new Obj().with("kind", "7 distinct items with the counts 1000, 100, 10, 2, 1, 3, 9").with("count", 1125);
      write("frequency", "freq_long_m64_exact7", l.toByteArray(), in, params(LONG, 64, "FrequentLongsSketch"), expectedLongs(l.toByteArray()));
      write("frequency", "freq_itemslong_m64_exact7", il.toByteArray(new ArrayOfLongsSerDe()), in,
          params(LONG, 64, "FrequentItemsSketch<Long> with ArrayOfLongsSerDe"), expectedLongItems(il.toByteArray(new ArrayOfLongsSerDe())));
      write("frequency", "freq_string_m64_exact7", st.toByteArray(new ArrayOfStringsSerDe()), in,
          params(STRING, 64, "FrequentItemsSketch<String> with ArrayOfStringsSerDe"), expectedStrings(st.toByteArray(new ArrayOfStringsSerDe())));
      System.out.println("m64 exact7: Longs " + Arrays.equals(l.toByteArray(), il.toByteArray(new ArrayOfLongsSerDe()))
          + " (equal to ItemsSketch<Long> bytes?), " + l.toByteArray().length + " vs " + il.toByteArray(new ArrayOfLongsSerDe()).length + " bytes");
    }
    // 40 distinct items, below the capacity of 48 for maxMapSize 64: exact, maximum error 0
    shape("exact40", 64, java.util.stream.LongStream.range(0, 40).flatMap(i -> java.util.stream.LongStream.range(0, i + 1).map(x -> i)).toArray(),
        new Obj().with("kind", "40 distinct items, item i seen i+1 times").with("count", 820));
    // more distinct items than the table holds: purged, maximum error above 0
    shape("purged", 8, skewed(2000, 300, 1L),
        new Obj().with("kind", "2000 events over up to 300 distinct items, skewed (java.util.Random seed 1)").with("count", 2000));
    shape("purged", 64, skewed(20000, 3000, 2L),
        new Obj().with("kind", "20000 events over up to 3000 distinct items, skewed (java.util.Random seed 2)").with("count", 20000));
    shape("purged", 1024, skewed(200000, 50000, 3L),
        new Obj().with("kind", "200000 events over up to 50000 distinct items, skewed (java.util.Random seed 3)").with("count", 200000));
  }

  /** Adds an existing string Frequency blob (the owner's own data) with manifest values read back by the library. */
  static void genField(Path bin, String name) throws IOException {
    byte[] b = Files.readAllBytes(bin);
    write("frequency", name, b, new Obj().with("kind", "the owner's own data, a table of browser platform names; writer unknown; reconstructed from a chat paste and checked (the counts add up to the stream length)"),
        params(STRING, 64, "unknown (field data)"), expectedStrings(b));
  }

  public static void main(String[] a) throws Exception {
    out = Paths.get(a[0]);
    if (a.length > 4 && a[3].equals("--field")) {   // GenFreq <outDir> <label> <version> --field <file.bin> <name>
      script = a[1]; libVersion = a[2];
      genField(Paths.get(a[4]), a[5]);
      System.out.println("wrote the field blob " + a[5] + " to " + out);
      return;
    }
    script = a.length > 1 ? a[1] : "fixturegen/jvm/genfreq";
    libVersion = a.length > 2 ? a[2] : "unknown";
    genFrequency();
    System.out.println("wrote " + written + " blobs to " + out + " with datasketches-java " + libVersion + " on JDK "
        + System.getProperty("java.version"));
  }
}
