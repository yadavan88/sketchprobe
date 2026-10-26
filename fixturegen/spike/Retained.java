import org.apache.datasketches.common.Util;
import org.apache.datasketches.kll.*;
import org.apache.datasketches.theta.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.*;
import java.util.*;

/** Lists the retained Theta hashes and KLL items that library 9.0.0 reports, for comparison with our decoder. */
public class Retained {
  public static void main(String[] a) throws Exception {
    for (String line : Files.readAllLines(Paths.get(a[0]))) {
      String[] c = line.split("\t", -1);
      byte[] b = Files.readAllBytes(Paths.get(c[0]));
      MemorySegment seg = MemorySegment.ofArray(b);
      StringBuilder out = new StringBuilder(c[4]).append('\t');
      try {
        if (c[1].equals("theta")) {
          long seed = Long.parseLong(c[3]); int fam = b[2]; ThetaSketch s;
          if (fam == 4) s = ThetaUnion.wrap(seg, seed).getResult(); else if (fam == 5) s = ThetaIntersection.wrap(seg, seed).getResult(); else s = ThetaSketch.heapify(seg, seed);
          List<Long> hs = new ArrayList<>(); HashIterator it = s.iterator(); while (it.next()) hs.add(it.get());
          Collections.sort(hs);
          out.append("THETA\tseedhash=").append(Util.computeSeedHash(seed) & 0xFFFF).append('\t');
          for (int i = 0; i < hs.size(); i++) out.append(i > 0 ? "," : "").append(hs.get(i));
        } else if (c[1].equals("kll")) {
          List<String> vs = new ArrayList<>(); long weight = 0;
          switch (c[2]) {
            case "float" -> { KllFloatsSketch k = KllFloatsSketch.heapify(seg); var it = k.iterator(); while (it.next()) { vs.add(Double.toString(it.getQuantile())); weight += it.getWeight(); } }
            case "double" -> { KllDoublesSketch k = KllDoublesSketch.heapify(seg); var it = k.iterator(); while (it.next()) { vs.add(Double.toString(it.getQuantile())); weight += it.getWeight(); } }
            default -> { KllLongsSketch k = KllLongsSketch.heapify(seg); var it = k.iterator(); while (it.next()) { vs.add(Long.toString(it.getQuantile())); weight += it.getWeight(); } }
          }
          out.append("KLL\tweight=").append(weight).append('\t').append(String.join(",", vs));
        } else continue;
      } catch (Throwable e) { out.append("ERROR\t").append(e.getClass().getSimpleName()).append('\t'); }
      System.out.println(out);
    }
  }
}
