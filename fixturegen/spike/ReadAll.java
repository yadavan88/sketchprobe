import org.apache.datasketches.hll.HllSketch;
import org.apache.datasketches.kll.*;
import org.apache.datasketches.theta.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.*;
import java.util.*;

/** Reads every corpus blob with datasketches-java 9.0.0 and prints what the library says, one line per blob. */
public class ReadAll {
  static final double[] RANKS = {0, 0.01, 0.05, 0.25, 0.5, 0.75, 0.95, 0.99, 1};
  public static void main(String[] a) throws Exception {
    for (String line : Files.readAllLines(Paths.get(a[0]))) {
      String[] c = line.split("\t", -1);
      String rel = c[4];
      StringBuilder out = new StringBuilder(rel).append('\t');
      try {
        byte[] b = Files.readAllBytes(Paths.get(c[0]));
        MemorySegment seg = MemorySegment.ofArray(b);
        switch (c[1]) {
          case "hll" -> {
            HllSketch h = HllSketch.heapify(seg);
            out.append("estimate=").append(h.getEstimate());
            for (int sd = 1; sd <= 3; sd++) out.append(";lowerBound").append(sd).append('=').append(h.getLowerBound(sd)).append(";upperBound").append(sd).append('=').append(h.getUpperBound(sd));
          }
          case "theta" -> {
            long seed = Long.parseLong(c[3]);
            int fam = b[2];
            ThetaSketch s;
            if (fam == 4) s = ThetaUnion.wrap(seg, seed).getResult();
            else if (fam == 5) s = ThetaIntersection.wrap(seg, seed).getResult();
            else s = ThetaSketch.heapify(seg, seed);
            out.append("estimate=").append(s.getEstimate()).append(";theta=").append(s.getTheta()).append(";retainedValid=").append(s.getRetainedEntries(true));
            for (int sd = 1; sd <= 3; sd++) out.append(";lowerBound").append(sd).append('=').append(s.getLowerBound(sd)).append(";upperBound").append(sd).append('=').append(s.getUpperBound(sd));
          }
          default -> {
            String t = c[2];
            if (t.equals("float")) { KllFloatsSketch k = KllFloatsSketch.heapify(seg); out.append("n=").append(k.getN()).append(";retained=").append(k.getNumRetained()); if (!k.isEmpty()) { out.append(";min=").append(k.getMinItem()).append(";max=").append(k.getMaxItem()); for (double r : RANKS) out.append(";q").append(r).append('=').append(k.getQuantile(r)); } }
            else if (t.equals("double")) { KllDoublesSketch k = KllDoublesSketch.heapify(seg); out.append("n=").append(k.getN()).append(";retained=").append(k.getNumRetained()); if (!k.isEmpty()) { out.append(";min=").append(k.getMinItem()).append(";max=").append(k.getMaxItem()); for (double r : RANKS) out.append(";q").append(r).append('=').append(k.getQuantile(r)); } }
            else { KllLongsSketch k = KllLongsSketch.heapify(seg); out.append("n=").append(k.getN()).append(";retained=").append(k.getNumRetained()); if (!k.isEmpty()) { out.append(";min=").append(k.getMinItem()).append(";max=").append(k.getMaxItem()); for (double r : RANKS) out.append(";q").append(r).append('=').append(k.getQuantile(r)); } }
          }
        }
      } catch (Throwable e) {
        out.append("ERROR=").append(e.getClass().getSimpleName()).append(": ").append(String.valueOf(e.getMessage()).replace('\n', ' '));
      }
      System.out.println(out);
    }
  }
}
