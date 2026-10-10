using System;
using System.Drawing;
using System.Linq;
using System.Threading;
using System.Windows.Forms;

namespace PdaNext
{
    static class Program
    {
        public static Icon AppIcon { get; private set; }

        [STAThread]
        static void Main(string[] args)
        {
            using (var mutex = new Mutex(true, @"Global\PdaNext.SingleInstance", out bool first))
            {
                if (!first)
                {
                    MessageBox.Show("PdaNext is already running. Look for its icon next to the clock.",
                        "PdaNext", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return;
                }

                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                using (var s = typeof(Program).Assembly.GetManifestResourceStream("app.ico"))
                    AppIcon = s != null ? new Icon(s) : SystemIcons.Application;

                bool tray = args.Contains("--tray");
                using (var bridge = new Bridge())
                {
                    var form = new MainForm(bridge, tray);
                    form.HandleCreated += (s, e) => bridge.Start(AppSettings.AutoConnect);
                    Application.Run(form);
                }
            }
        }
    }
}
