// Dumps every net.zodac.diurnal.web.AppMessages entry's DEFAULT (English/en-GB) text, read via reflection off
// the compiled @Message annotations, as sorted key=value .properties lines on stdout.
//
// Reflection, not source parsing: a handful of @Message values are written as multi-line Java string
// concatenation in AppMessages.java, and javac folds that into a single constant string by the time the
// annotation is compiled - reading the annotation's resolved value() sidesteps every source-layout edge case
// a regex/text-based extractor would otherwise have to handle.
//
// Not compiled or run as part of `mvn package` - see scripts/generate-source-messages.sh, which builds and
// invokes this against the already-compiled target/classes.
import io.quarkus.qute.i18n.Message;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

public final class GenerateSourceMessages {

    private GenerateSourceMessages() {

    }

    public static void main(final String[] args) throws ClassNotFoundException {
        final Class<?> appMessages = Class.forName("net.zodac.diurnal.web.AppMessages");
        final PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        final Method[] methods = appMessages.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));

        for (final Method method : methods) {
            final Message message = method.getAnnotation(Message.class);
            if (message == null) {
                continue;
            }
            out.println(method.getName() + "=" + escape(message.value()));
        }
    }

    // Standard java.util.Properties value escaping - a literal backslash or newline is the only thing that
    // would otherwise corrupt the line-oriented format; every other character (including non-ASCII, since this
    // project's bundle files are read as UTF-8 - see AppMessagesIT) passes through unescaped, matching the
    // existing hand-written msg_*.properties files' own style.
    private static String escape(final String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n");
    }
}
