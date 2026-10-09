package zcm.spy;

/** DPI regression tests that do not initialize AWT or require an X server. */
public class SpyUiScaleTest
{
    public static void main(String[] args)
    {
        if (args.length == 1) {
            SpyUiScale.configure();
            String actual = System.getProperty("sun.java2d.uiScale", "automatic");
            if (!args[0].equals(actual))
                throw new AssertionError("Expected " + args[0] + ", got " + actual);
            return;
        }

        assert SpyUiScale.scaleForDpi(96) == 1;
        assert SpyUiScale.scaleForDpi(120) == 1;
        assert SpyUiScale.scaleForDpi(144) == 1;
        assert SpyUiScale.scaleForDpi(148) == 1;
        assert SpyUiScale.scaleForDpi(162) == 1;
        assert SpyUiScale.scaleForDpi(191.9) == 1;
        assert SpyUiScale.scaleForDpi(192) == 2;
        assert SpyUiScale.scaleForDpi(240) == 2;
        assert SpyUiScale.scaleForDpi(288) == 3;
        assert SpyUiScale.scaleForDpi(384) == 4;
        String normal = " 0: +*eDP-1 1920/301x1200/188+0+0  eDP-1\n";
        String dense = " 1: +DP-1 3840/340x2160/190+1920+0  DP-1\n";
        assert SpyUiScale.scaleForDpi(SpyUiScale.parseMonitorDpi(normal)) == 1;
        assert SpyUiScale.scaleForDpi(SpyUiScale.parseMonitorDpi(normal + dense)) == 1;
        assert SpyUiScale.scaleForDpi(SpyUiScale.parseMonitorDpi(dense + normal)) == 1;
        assert SpyUiScale.scaleForDpi(SpyUiScale.parseMonitorDpi(dense)) == 2;
        assert SpyUiScale.parseMonitorDpi(normal.replace("*", "") + dense) == -1;
        assert SpyUiScale.parseMonitorDpi("0: +*DP-1 1920/0x1080/0+0+0 DP-1") == -1;
        assert SpyUiScale.parseMonitorDpi("Monitors: 0\n") == -1;
        assert SpyUiScale.parseXftDpi("Xft.antialias:\t1\nXft.dpi:\t192\n") == 192;
        assert SpyUiScale.parseXftDpi(" Xft.dpi:  143.5 \n") == 143.5;
        assert SpyUiScale.parseXftDpi("Xft.dpi: invalid\n") == -1;
        assert SpyUiScale.parseXftDpi("Xft.dpi: 0\n") == -1;
        assert SpyUiScale.parseXftDpi("") == -1;
        assert SpyUiScale.parseResolution("  resolution:    192x194 dots per inch\n") == 193;
        assert SpyUiScale.parseResolution("  resolution:    0x192 dots per inch\n") == -1;
        assert SpyUiScale.parseResolution("xdpyinfo: unable to open display") == -1;
        assert SpyUiScale.query("/nonexistent/zcm-spy-dpi-query").isEmpty();
        assert SpyUiScale.query("sh", "-c", "echo failed >&2; exit 1").isEmpty();
        long start = System.nanoTime();
        assert SpyUiScale.query("sh", "-c", "exec sleep 10").isEmpty();
        assert System.nanoTime() - start < 5_000_000_000L : "DPI query did not time out";
        System.out.println("Spy UI scale tests passed");
    }
}
