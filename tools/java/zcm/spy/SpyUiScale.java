package zcm.spy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Linux DPI detection without initializing AWT (which caches the UI scale). */
final class SpyUiScale
{
    private static final Pattern XFT_DPI = Pattern.compile(
        "(?m)^\\s*Xft\\.dpi\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*$");
    private static final Pattern RESOLUTION = Pattern.compile(
        "(?m)^\\s*resolution:\\s*([0-9]+)x([0-9]+)\\s+dots per inch\\s*$");
    private static final Pattern MONITOR = Pattern.compile(
        "(?m)^\\s*\\d+:\\s+(\\S+)\\s+(\\d+)/(\\d+)x(\\d+)/(\\d+)[+-]\\d+[+-]\\d+.*$");

    static void configure()
    {
        // Other platforms already have native scaling. Respect explicit Java
        // overrides and GDK_SCALE, which Java's X11 backend reads itself.
        if (!System.getProperty("os.name", "").startsWith("Linux") ||
            System.getenv("DISPLAY") == null ||
            System.getProperty("sun.java2d.uiScale") != null ||
            "false".equalsIgnoreCase(System.getProperty("sun.java2d.uiScale.enabled")) ||
            System.getenv("GDK_SCALE") != null)
            return;

        double dpi = parseXftDpi(query("xrdb", "-query"));
        String source = "Xft.dpi";
        if (dpi <= 0) {
            // Qt environment variables survive monitor changes and may include
            // disconnected outputs. They are not measurements of this display.
            dpi = parseMonitorDpi(query("xrandr", "--listactivemonitors"));
            source = "active monitor";
        }
        if (dpi <= 0) {
            dpi = parseResolution(query("xdpyinfo"));
            source = "xdpyinfo";
        }
        if (dpi <= 0)
            return; // Leave Java's native detection in place if detection fails.

        int scale = scaleForDpi(dpi);
        if (scale > 1)
            System.setProperty("sun.java2d.uiScale", Integer.toString(scale));
        System.out.printf(Locale.ROOT, "Screen DPI: %.1f (%s); UI scale: %s%n",
                          dpi, source, scale > 1 ? scale + "x" : "automatic");
    }

    static int scaleForDpi(double dpi)
    {
        // X11 Java2D supports integer scales. Do not turn a fractional DPI
        // setting (e.g. 144 DPI / 150%) into a forced 200% scale.
        return Double.isFinite(dpi) ? (int)Math.max(1, Math.floor(dpi / 96.0)) : 1;
    }

    static double parseMonitorDpi(String output)
    {
        Matcher match = MONITOR.matcher(output);
        double onlyDpi = -1;
        int count = 0;
        while (match.find()) {
            count++;
            double width = Double.parseDouble(match.group(2));
            double widthMm = Double.parseDouble(match.group(3));
            double height = Double.parseDouble(match.group(4));
            double heightMm = Double.parseDouble(match.group(5));
            double dpi = (width / widthMm + height / heightMm) * 25.4 / 2;
            if (!Double.isFinite(dpi) || width <= 0 || height <= 0 || widthMm <= 0 || heightMm <= 0)
                dpi = -1;
            // The primary monitor is the best startup target we can identify
            // before AWT initializes. Never use the largest scale across outputs.
            if (match.group(1).contains("*"))
                return dpi;
            onlyDpi = dpi;
        }
        return count == 1 ? onlyDpi : -1;
    }

    static double parseXftDpi(String output)
    {
        Matcher match = XFT_DPI.matcher(output);
        if (!match.find())
            return -1;
        double dpi = Double.parseDouble(match.group(1));
        return Double.isFinite(dpi) && dpi > 0 ? dpi : -1;
    }

    static double parseResolution(String output)
    {
        Matcher match = RESOLUTION.matcher(output);
        if (!match.find())
            return -1;
        double x = Double.parseDouble(match.group(1));
        double y = Double.parseDouble(match.group(2));
        double dpi = (x + y) / 2.0;
        return Double.isFinite(dpi) && x > 0 && y > 0 ? dpi : -1;
    }

    static String query(String... command)
    {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // Both queries normally have small output. A hung command (including
            // one filling its output pipe) must not prevent Spy from opening.
            if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0)
                return "";
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                     new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null)
                    output.append(line).append('\n');
            }
            return output.toString();
        } catch (IOException ex) {
            return ""; // Optional X11 utilities may not be installed.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (process != null)
                process.destroyForcibly();
        }
    }
}
