package zcm.spy;

import javax.swing.UIManager;

/** Desktop styling, with the JRE's Nimbus theme when native styling is unavailable. */
final class SpyAppearance
{
    static void configure()
    {
        if (System.getProperty("swing.defaultlaf") != null) return;
        String system = UIManager.getSystemLookAndFeelClassName();
        if (!system.equals(UIManager.getCrossPlatformLookAndFeelClassName()) && install(system)) return;
        if (System.getProperty("os.name", "").startsWith("Linux")) {
            for (UIManager.LookAndFeelInfo theme : UIManager.getInstalledLookAndFeels())
                if (theme.getName().equals("GTK+") && install(theme.getClassName())) return;
        }
        for (UIManager.LookAndFeelInfo theme : UIManager.getInstalledLookAndFeels())
            if (theme.getName().equals("Nimbus") && install(theme.getClassName())) return;
        install(system);
    }

    private static boolean install(String name)
    {
        try { UIManager.setLookAndFeel(name); return true; }
        catch (Exception unavailable) { return false; }
    }
}
