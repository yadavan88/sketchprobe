package sketchprobe.web;

import java.util.function.BiFunction;
import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSString;

/**
 * The entry point of the browser build. It is Java, not Scala, because the Web Image API ships inside
 * Oracle GraalVM and is not on Maven Central, so packaging/build-web.sh compiles this one file against
 * it. All the logic is in {@link Playground}.
 *
 * It puts one function on the page: {@code sketchprobe.inspect(value, options)}. {@code value} is the
 * text the user pasted; {@code options} is the command line options, one per line (an empty string for
 * none). It returns a JSON string, {@code {"status": 0, "out": "...", "err": "..."}}.
 */
public final class Bridge {
  private Bridge() {}

  @JS(
      args = {"inspect"},
      value = "globalThis.sketchprobe = { inspect: (value, options) => inspect(value, options) };")
  private static native void export(BiFunction<JSString, JSString, JSString> inspect);

  private static JSString inspect(JSString value, JSString options) {
    String text = options.asString();
    String[] argv = text.isEmpty() ? new String[0] : text.split("\n");
    return JSString.of(Playground.runJson(value.asString(), argv));
  }

  public static void main(String[] args) {
    export(Bridge::inspect);
  }
}
