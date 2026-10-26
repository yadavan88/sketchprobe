import org.apache.datasketches.hll.HllSketch;
import org.apache.datasketches.kll.*;
import org.apache.datasketches.theta.*;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.concurrent.*;

/** Runs each hostile blob through the library in its own thread and reports outcome, time and allocation. */
public class Forge {
  public static void main(String[] a) throws Exception {
    com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    for (String line : Files.readAllLines(Paths.get(a[0]))) {
      String[] c = line.split("\t", -1);
      byte[] b = Files.readAllBytes(Paths.get(c[0]));
      final long[] tid = {-1};
      Callable<String> job = () -> {
        tid[0] = Thread.currentThread().getId();
        MemorySegment seg = MemorySegment.ofArray(b);
        switch (c[1]) {
          case "hll" -> { HllSketch h = HllSketch.heapify(seg); return "ok estimate=" + h.getEstimate(); }
          case "theta" -> {
            long seed = Long.parseLong(c[3]); int fam = b.length > 2 ? b[2] : -1; ThetaSketch s;
            if (fam == 4) s = ThetaUnion.wrap(seg, seed).getResult(); else if (fam == 5) s = ThetaIntersection.wrap(seg, seed).getResult(); else s = ThetaSketch.heapify(seg, seed);
            return "ok estimate=" + s.getEstimate();
          }
          default -> { KllFloatsSketch k = KllFloatsSketch.heapify(seg); return "ok n=" + k.getN() + (k.isEmpty() ? "" : " median=" + k.getQuantile(0.5)); }
        }
      };
      ExecutorService ex = Executors.newSingleThreadExecutor();
      long t0 = System.nanoTime(); String outcome;
      Future<String> f = ex.submit(job);
      try { outcome = f.get(5, TimeUnit.SECONDS); }
      catch (TimeoutException e) { outcome = "TIMEOUT(>5s)"; f.cancel(true); }
      catch (ExecutionException e) { Throwable t = e.getCause(); outcome = (t instanceof OutOfMemoryError ? "OUT_OF_MEMORY" : "exception " + t.getClass().getSimpleName()); }
      long ms = (System.nanoTime() - t0) / 1_000_000;
      long alloc = tid[0] > 0 ? mx.getThreadAllocatedBytes(tid[0]) / 1_000_000 : -1;
      System.out.printf("%-48s %5d ms %6d MB  %s%n", c[4], ms, alloc, outcome);
      ex.shutdownNow();
    }
    System.exit(0);
  }
}
