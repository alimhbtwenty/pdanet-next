using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Linq;
using System.Net.NetworkInformation;
using System.Windows.Forms;

namespace PdaNext
{
    static class Theme
    {
        public static readonly Color Bg = Color.FromArgb(11, 15, 25);
        public static readonly Color Card = Color.FromArgb(20, 27, 45);
        public static readonly Color Stroke = Color.FromArgb(31, 41, 64);
        public static readonly Color Text = Color.FromArgb(241, 245, 249);
        public static readonly Color Secondary = Color.FromArgb(148, 163, 184);
        public static readonly Color Muted = Color.FromArgb(100, 116, 139);
        public static readonly Color Accent = Color.FromArgb(56, 189, 248);
        public static readonly Color On = Color.FromArgb(34, 197, 94);
        public static readonly Color Wait = Color.FromArgb(245, 158, 11);
        public static readonly Color Off = Color.FromArgb(100, 116, 139);
        public static readonly Color Error = Color.FromArgb(239, 68, 68);
        public static readonly Color Start = Color.FromArgb(37, 99, 235);
        public static readonly Color Stop = Color.FromArgb(220, 38, 38);

        public static Font UI(float size, FontStyle style = FontStyle.Regular) => new Font("Segoe UI", size, style);
    }

    /// <summary>Rounded card background.</summary>
    sealed class Card : Panel
    {
        public Card()
        {
            DoubleBuffered = true;
            BackColor = Theme.Bg;
            Padding = new Padding(16);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            var r = new Rectangle(0, 0, Width - 1, Height - 1);
            using (var path = Rounded(r, 14))
            using (var fill = new SolidBrush(Theme.Card))
            using (var pen = new Pen(Theme.Stroke))
            {
                e.Graphics.FillPath(fill, path);
                e.Graphics.DrawPath(pen, path);
            }
        }

        public static GraphicsPath Rounded(Rectangle r, int radius)
        {
            int d = radius * 2;
            var p = new GraphicsPath();
            p.AddArc(r.X, r.Y, d, d, 180, 90);
            p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
            p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
            p.CloseFigure();
            return p;
        }
    }

    sealed class Dot : Control
    {
        Color color = Theme.Off;
        public Color DotColor { get => color; set { color = value; Invalidate(); } }

        public Dot()
        {
            SetStyle(ControlStyles.SupportsTransparentBackColor | ControlStyles.OptimizedDoubleBuffer | ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint, true);
            BackColor = Theme.Card;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (var b = new SolidBrush(color)) e.Graphics.FillEllipse(b, 0, 0, Width - 1, Height - 1);
        }
    }

    public sealed class MainForm : Form
    {
        readonly Bridge bridge;
        readonly NotifyIcon tray;
        readonly bool startHidden;
        bool exiting;
        bool updatingDevices;
        bool trayHintShown;

        Dot statusDot;
        Label lblStatus, lblDetail, lblPhone, lblUplink, lblVpn, lblDown, lblUp, lblTotal, lblRouterPing, lblNetPing;
        Button btnConnect, btnUnblock;
        ComboBox cbDevice;
        CheckBox chkAuto, chkStartup, chkDns, chkKillSwitch;
        readonly Timer pingTimer = new Timer { Interval = 2000 };
        bool pinging;
        TextBox txtLog;
        LinkLabel lnkLog;
        Button btnSaveDiag, btnClearLogs;
        ToolStripMenuItem trayConnect, trayStatus, trayUnblock;

        Card vpnCard;
        Panel pnlVpnList;
        Label lblVpnNote;
        Button btnPhoneScreen;
        LinkLabel lnkRefreshVpn;
        bool refreshingVpns;

        PhoneStatus lastStatus;
        DateTime lastStatusAt;
        List<AdbDevice> devices = new List<AdbDevice>();

        public MainForm(Bridge bridge, bool startHidden)
        {
            this.bridge = bridge;
            this.startHidden = startHidden;

            Text = "PdaNext";
            Icon = Program.AppIcon;
            BackColor = Theme.Bg;
            ForeColor = Theme.Text;
            Font = Theme.UI(9.5f);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            AutoScaleMode = AutoScaleMode.Dpi;
            ClientSize = new Size(480, 920);
            StartPosition = FormStartPosition.CenterScreen;

            BuildUi();

            tray = new NotifyIcon { Icon = Program.AppIcon, Text = "PdaNext", Visible = true };
            var menu = new ContextMenuStrip();
            trayStatus = new ToolStripMenuItem("Not connected") { Enabled = false };
            trayUnblock = new ToolStripMenuItem("Unblock internet", null, (s, e) => bridge.RequestUnblock()) { Visible = false };
            trayConnect = new ToolStripMenuItem("Connect", null, (s, e) => ToggleConnection());
            menu.Items.Add(trayStatus);
            menu.Items.Add(trayUnblock);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(new ToolStripMenuItem("Open", null, (s, e) => ShowFromTray()));
            menu.Items.Add(trayConnect);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(new ToolStripMenuItem("Exit", null, (s, e) => ExitApp()));
            tray.ContextMenuStrip = menu;
            tray.DoubleClick += (s, e) => ShowFromTray();

            bridge.StateChanged += (state, msg) => Ui(() => RenderState(state, msg));
            bridge.Log += line => Ui(() => AppendLog(line));
            bridge.StatusUpdated += status => Ui(() => RenderStatus(status));
            bridge.DevicesChanged += list => Ui(() => RenderDevices(list));
        }

        protected override void SetVisibleCore(bool value)
        {
            // Started from the logon task with --tray: stay in the tray until opened.
            if (startHidden && !IsHandleCreated)
            {
                CreateHandle();
                value = false;
            }
            base.SetVisibleCore(value);
        }

        void BuildUi()
        {
            int x = 18, w = ClientSize.Width - 36, y = 16;

            Controls.Add(new PictureBox
            {
                Image = new Icon(Program.AppIcon, 48, 48).ToBitmap(),
                SizeMode = PictureBoxSizeMode.Zoom,
                Location = new Point(x, y + 4),
                Size = new Size(52, 52),
                BackColor = Color.Transparent,
            });
            Controls.Add(MakeLabel("PdaNext", Theme.UI(20f, FontStyle.Bold), Theme.Text, x + 64, y, w - 64));
            Controls.Add(MakeLabel("Your phone as this PC's router, over USB", Theme.UI(10f), Theme.Secondary, x + 66, y + 38, w - 66));
            y += 74;

            // Status card
            var status = AddCard(x, y, w, 120);
            statusDot = new Dot { Location = new Point(18, 24), Size = new Size(14, 14) };
            status.Controls.Add(statusDot);
            lblStatus = MakeLabel("Not connected", Theme.UI(15f, FontStyle.Bold), Theme.Text, 40, 14, w - 60);
            status.Controls.Add(lblStatus);
            lblDetail = MakeLabel("", Theme.UI(9.5f), Theme.Secondary, 18, 50, w - 36, 60);
            status.Controls.Add(lblDetail);
            y += 132;

            // Phone card
            var phone = AddCard(x, y, w, 128);
            int col = (w - 36) / 2;
            phone.Controls.Add(MakeCaption("PHONE", 18, 14));
            cbDevice = new ComboBox
            {
                DropDownStyle = ComboBoxStyle.DropDownList,
                Location = new Point(18, 34),
                Width = w - 36,
                FlatStyle = FlatStyle.Flat,
                BackColor = Theme.Card,
                ForeColor = Theme.Text,
            };
            cbDevice.SelectedIndexChanged += (s, e) => { if (!updatingDevices && bridge.WantsConnection) Connect(); };
            phone.Controls.Add(cbDevice);
            lblPhone = MakeLabel("No phone found", Theme.UI(10f, FontStyle.Bold), Theme.Text, 18, 36, w - 36);
            phone.Controls.Add(lblPhone);
            phone.Controls.Add(MakeCaption("PHONE INTERNET", 18, 72));
            lblUplink = MakeLabel("—", Theme.UI(10.5f, FontStyle.Bold), Theme.Text, 18, 92, col);
            phone.Controls.Add(lblUplink);
            phone.Controls.Add(MakeCaption("PHONE VPN", 18 + col, 72));
            lblVpn = MakeLabel("—", Theme.UI(10.5f, FontStyle.Bold), Theme.Text, 18 + col, 92, col);
            phone.Controls.Add(lblVpn);
            y += 140;

            btnConnect = new Button
            {
                Text = "Connect",
                Location = new Point(x, y),
                Size = new Size(w, 54),
                FlatStyle = FlatStyle.Flat,
                BackColor = Theme.Start,
                ForeColor = Color.White,
                Font = Theme.UI(13f, FontStyle.Bold),
                Cursor = Cursors.Hand,
            };
            btnConnect.FlatAppearance.BorderSize = 0;
            btnConnect.Click += (s, e) => ToggleConnection();
            Controls.Add(btnConnect);

            btnUnblock = new Button
            {
                Text = "Unblock",
                Location = new Point(x + (w - 10) / 2 + 10, y),
                Size = new Size((w - 10) / 2, 54),
                FlatStyle = FlatStyle.Flat,
                BackColor = Theme.Card,
                ForeColor = Theme.Text,
                Font = Theme.UI(12f, FontStyle.Bold),
                Cursor = Cursors.Hand,
                Visible = false,
            };
            btnUnblock.FlatAppearance.BorderColor = Theme.Stroke;
            btnUnblock.FlatAppearance.BorderSize = 1;
            btnUnblock.Click += (s, e) => bridge.RequestUnblock();
            Controls.Add(btnUnblock);
            y += 66;

            // VPN card
            vpnCard = AddCard(x, y, w, 192);
            vpnCard.Controls.Add(MakeCaption("VPN APPS", 18, 14));

            btnPhoneScreen = new Button
            {
                Text = "Phone screen",
                Location = new Point(w - 18 - 110, 10),
                Size = new Size(110, 26),
                FlatStyle = FlatStyle.Flat,
                BackColor = Theme.Stroke,
                ForeColor = Theme.Text,
                Font = Theme.UI(8.5f, FontStyle.Bold),
                Cursor = Cursors.Hand,
            };
            btnPhoneScreen.FlatAppearance.BorderSize = 0;
            btnPhoneScreen.Click += (s, e) =>
            {
                try { Scrcpy.Start(SelectedSerial); }
                catch (Exception ex)
                {
                    MessageBox.Show("Could not start scrcpy: " + ex.Message, "PdaNext", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                }
            };
            vpnCard.Controls.Add(btnPhoneScreen);

            lblVpnNote = MakeLabel("Starting another VPN stops the current one", Theme.UI(8.5f), Theme.Muted, 18, 38, w - 80);
            vpnCard.Controls.Add(lblVpnNote);

            lnkRefreshVpn = new LinkLabel
            {
                Text = "Refresh",
                Location = new Point(w - 18 - 50, 38),
                AutoSize = true,
                LinkColor = Theme.Accent,
                ActiveLinkColor = Theme.Accent,
                Font = Theme.UI(8.5f),
            };
            lnkRefreshVpn.LinkClicked += (s, e) => RefreshVpnApps();
            vpnCard.Controls.Add(lnkRefreshVpn);

            pnlVpnList = new Panel
            {
                Location = new Point(18, 58),
                Size = new Size(w - 36, 122),
                AutoScroll = true,
                BackColor = Color.Transparent,
            };
            vpnCard.Controls.Add(pnlVpnList);
            RenderVpnApps(null, "Connect a phone to view VPN apps");
            y += 204;

            // Traffic card
            var traffic = AddCard(x, y, w, 138);
            int third = (w - 36) / 3;
            traffic.Controls.Add(MakeCaption("DOWNLOAD", 18, 14));
            traffic.Controls.Add(MakeCaption("UPLOAD", 18 + third, 14));
            traffic.Controls.Add(MakeCaption("THIS SESSION", 18 + 2 * third, 14));
            lblDown = MakeLabel("0 KB/s", Theme.UI(11f, FontStyle.Bold), Theme.Accent, 18, 34, third);
            lblUp = MakeLabel("0 KB/s", Theme.UI(11f, FontStyle.Bold), Theme.Text, 18 + third, 34, third);
            lblTotal = MakeLabel("0 MB", Theme.UI(11f, FontStyle.Bold), Theme.Text, 18 + 2 * third, 34, third);
            traffic.Controls.AddRange(new Control[] { lblDown, lblUp, lblTotal });
            traffic.Controls.Add(MakeCaption("PING ROUTER (PHONE)", 18, 74));
            traffic.Controls.Add(MakeCaption("PING INTERNET (1.1.1.1)", 18 + (w - 36) / 2, 74));
            lblRouterPing = MakeLabel("—", Theme.UI(11f, FontStyle.Bold), Theme.Text, 18, 94, (w - 36) / 2);
            lblNetPing = MakeLabel("—", Theme.UI(11f, FontStyle.Bold), Theme.Text, 18 + (w - 36) / 2, 94, (w - 36) / 2);
            traffic.Controls.AddRange(new Control[] { lblRouterPing, lblNetPing });
            y += 150;

            chkAuto = MakeCheck("Connect automatically when the phone is plugged in", x + 4, y, w);
            chkAuto.Checked = AppSettings.AutoConnect;
            chkAuto.CheckedChanged += (s, e) => AppSettings.AutoConnect = chkAuto.Checked;
            y += 28;
            chkStartup = MakeCheck("Start with Windows (in the tray)", x + 4, y, w);
            chkStartup.Checked = AppSettings.StartWithWindows;
            chkStartup.CheckedChanged += (s, e) => AppSettings.StartWithWindows = chkStartup.Checked;
            y += 28;
            chkDns = MakeCheck("Send all DNS to the phone (no DNS leaks outside the VPN)", x + 4, y, w);
            chkDns.Checked = AppSettings.PreventDnsLeaks;
            chkDns.CheckedChanged += (s, e) => AppSettings.PreventDnsLeaks = chkDns.Checked;
            y += 28;
            chkKillSwitch = MakeCheck("Kill switch (block internet if the phone link drops)", x + 4, y, w);
            chkKillSwitch.Checked = AppSettings.KillSwitch;
            chkKillSwitch.CheckedChanged += (s, e) =>
            {
                AppSettings.KillSwitch = chkKillSwitch.Checked;
                if (!chkKillSwitch.Checked)
                {
                    bridge.RequestUnblock();
                }
            };
            Controls.AddRange(new Control[] { chkAuto, chkStartup, chkDns, chkKillSwitch });

            pingTimer.Tick += async (s, e) => await PingOnce();
            pingTimer.Start();
            y += 34;

            lnkLog = new LinkLabel
            {
                Text = "Show log",
                Location = new Point(x + 2, y + 4),
                AutoSize = true,
                LinkColor = Theme.Accent,
                ActiveLinkColor = Theme.Accent,
                Font = Theme.UI(9f),
            };
            lnkLog.LinkClicked += (s, e) => ToggleLog();
            Controls.Add(lnkLog);

            btnClearLogs = new Button
            {
                Text = "Clear logs",
                Location = new Point(x + w - 130 - 100 - 8, y),
                Size = new Size(100, 26),
                FlatStyle = FlatStyle.Flat,
                BackColor = Theme.Card,
                ForeColor = Theme.Text,
                Font = Theme.UI(8.5f),
                Cursor = Cursors.Hand,
            };
            btnClearLogs.FlatAppearance.BorderSize = 1;
            btnClearLogs.FlatAppearance.BorderColor = Theme.Stroke;
            btnClearLogs.Click += (s, e) => ClearLogsClicked();
            Controls.Add(btnClearLogs);

            btnSaveDiag = new Button
            {
                Text = "Save diagnostics",
                Location = new Point(x + w - 130, y),
                Size = new Size(130, 26),
                FlatStyle = FlatStyle.Flat,
                BackColor = Theme.Card,
                ForeColor = Theme.Text,
                Font = Theme.UI(8.5f),
                Cursor = Cursors.Hand,
            };
            btnSaveDiag.FlatAppearance.BorderSize = 1;
            btnSaveDiag.FlatAppearance.BorderColor = Theme.Stroke;
            btnSaveDiag.Click += async (s, e) => await SaveDiagnosticsAsync();
            Controls.Add(btnSaveDiag);
            y += 32;

            txtLog = new TextBox
            {
                Multiline = true,
                ReadOnly = true,
                ScrollBars = ScrollBars.Vertical,
                Location = new Point(x, y),
                Size = new Size(w, 150),
                BackColor = Theme.Card,
                ForeColor = Theme.Secondary,
                BorderStyle = BorderStyle.FixedSingle,
                Font = new Font("Consolas", 8.5f),
                Visible = false,
            };
            Controls.Add(txtLog);
        }

        Card AddCard(int x, int y, int w, int h)
        {
            var c = new Card { Location = new Point(x, y), Size = new Size(w, h) };
            Controls.Add(c);
            return c;
        }

        static Label MakeLabel(string text, Font font, Color color, int x, int y, int w, int h = 0) => new Label
        {
            Text = text,
            Font = font,
            ForeColor = color,
            BackColor = Color.Transparent,
            Location = new Point(x, y),
            AutoSize = h == 0,
            MaximumSize = new Size(w, 0),
            Size = h == 0 ? Size.Empty : new Size(w, h),
        };

        static Label MakeCaption(string text, int x, int y) =>
            MakeLabel(text, Theme.UI(7.5f, FontStyle.Bold), Theme.Muted, x, y, 200);

        static CheckBox MakeCheck(string text, int x, int y, int w) => new CheckBox
        {
            Text = text,
            Location = new Point(x, y),
            Size = new Size(w, 24),
            ForeColor = Theme.Secondary,
            BackColor = Theme.Bg,
            Font = Theme.UI(9f),
        };

        string SelectedSerial => cbDevice.SelectedIndex >= 0 && cbDevice.SelectedIndex < devices.Count ? devices[cbDevice.SelectedIndex].Serial : null;

        void ToggleConnection()
        {
            if (bridge.WantsConnection) bridge.RequestDisconnect();
            else Connect();
            RenderButton();
        }

        void Connect() => bridge.RequestConnect(SelectedSerial);

        void RenderButton()
        {
            bool on = bridge.WantsConnection;
            btnConnect.Text = on ? "Disconnect" : "Connect";
            btnConnect.BackColor = on ? Theme.Stop : Theme.Start;
            trayConnect.Text = on ? "Disconnect" : "Connect";
        }

        void RenderState(BridgeState state, string message)
        {
            string title;
            Color color;
            switch (state)
            {
                case BridgeState.Connected: title = "Connected"; color = Theme.On; break;
                case BridgeState.Connecting: title = "Connecting…"; color = Theme.Wait; break;
                case BridgeState.Waiting: title = "Waiting for phone"; color = Theme.Wait; break;
                case BridgeState.Error: title = "Problem"; color = Theme.Error; break;
                case BridgeState.Blocked: title = "Kill switch active"; color = Theme.Error; break;
                default: title = "Not connected"; color = Theme.Off; break;
            }
            bool changed = lblStatus.Text != title;
            lblStatus.Text = title;
            lblDetail.Text = message;
            statusDot.DotColor = color;
            trayStatus.Text = title;
            tray.Text = ("PdaNext: " + title).Substring(0, Math.Min(63, ("PdaNext: " + title).Length));
            if (state != BridgeState.Connected)
            {
                lblDown.Text = lblUp.Text = "0 KB/s";
                lastStatus = null;
            }
            if (btnUnblock != null)
            {
                bool blocked = state == BridgeState.Blocked;
                btnUnblock.Visible = blocked;
                int w = ClientSize.Width - 36;
                btnConnect.Width = blocked ? (w - 10) / 2 : w;
            }
            if (trayUnblock != null)
            {
                trayUnblock.Visible = state == BridgeState.Blocked;
            }
            RenderButton();
            if (changed && (state == BridgeState.Connected || state == BridgeState.Error || state == BridgeState.Blocked || (state == BridgeState.Waiting && message.StartsWith("Phone unplugged"))))
            {
                tray.ShowBalloonTip(3000, "PdaNext: " + title, message,
                    state == BridgeState.Error || state == BridgeState.Blocked ? ToolTipIcon.Warning : ToolTipIcon.Info);
            }
        }

        void RenderStatus(PhoneStatus s)
        {
            if (!string.IsNullOrEmpty(s.Device)) lblPhone.Text = s.Device;
            lblUplink.Text = s.UplinkDisplay;
            lblVpn.Text = s.VpnActive ? "On (PC uses it too)" : "Off";
            lblVpn.ForeColor = s.VpnActive ? Theme.On : Theme.Secondary;

            var now = DateTime.UtcNow;
            if (lastStatus != null)
            {
                double secs = Math.Max(0.5, (now - lastStatusAt).TotalSeconds);
                lblDown.Text = Rate(Math.Max(0, s.BytesDown - lastStatus.BytesDown) / secs);
                lblUp.Text = Rate(Math.Max(0, s.BytesUp - lastStatus.BytesUp) / secs);
            }
            lblTotal.Text = FormatSize(s.BytesDown + s.BytesUp);
            lastStatus = s;
            lastStatusAt = now;
        }

        void RenderDevices(List<AdbDevice> list)
        {
            string selected = SelectedSerial;
            devices = list;
            updatingDevices = true;
            cbDevice.BeginUpdate();
            cbDevice.Items.Clear();
            foreach (var d in list) cbDevice.Items.Add(d.DisplayName + (d.Ready ? "" : "  (" + d.State + ")"));
            int index = list.FindIndex(d => d.Serial == selected);
            if (index < 0 && list.Count > 0) index = Math.Max(0, list.FindIndex(d => d.Ready));
            if (index >= 0) cbDevice.SelectedIndex = index;
            cbDevice.EndUpdate();
            updatingDevices = false;
            // A dropdown only when there is a choice to make.
            cbDevice.Visible = list.Count > 1;
            lblPhone.Visible = list.Count <= 1;
            if (list.Count == 0) lblPhone.Text = "No phone found";
            else if (list.Count == 1) lblPhone.Text = list[0].DisplayName + (list[0].Ready ? "" : "  (" + list[0].State + ")");

            RefreshVpnApps();
        }

        async void RefreshVpnApps()
        {
            if (refreshingVpns) return;
            string serial = SelectedSerial;
            if (string.IsNullOrEmpty(serial))
            {
                RenderVpnApps(null, "Connect a phone to view VPN apps");
                return;
            }

            refreshingVpns = true;
            RenderVpnApps(null, "Scanning VPN apps…");
            try
            {
                var apps = await Adb.ListVpnAppsAsync(serial);
                Ui(() => RenderVpnApps(apps, apps.Count == 0 ? "No VPN apps found on phone" : null));
            }
            catch (Exception ex)
            {
                Ui(() => RenderVpnApps(null, "Error scanning VPN apps: " + ex.Message));
            }
            finally
            {
                refreshingVpns = false;
            }
        }

        void RenderVpnApps(List<VpnApp> apps, string message)
        {
            pnlVpnList.SuspendLayout();
            pnlVpnList.Controls.Clear();

            if (!string.IsNullOrEmpty(message))
            {
                var lblMsg = MakeLabel(message, Theme.UI(9f), Theme.Muted, 4, 10, pnlVpnList.Width - 10);
                pnlVpnList.Controls.Add(lblMsg);
                pnlVpnList.ResumeLayout();
                return;
            }

            if (apps == null || apps.Count == 0)
            {
                var lblMsg = MakeLabel("No VPN apps found on phone", Theme.UI(9f), Theme.Muted, 4, 10, pnlVpnList.Width - 10);
                pnlVpnList.Controls.Add(lblMsg);
                pnlVpnList.ResumeLayout();
                return;
            }

            int rowY = 0;
            int rowW = pnlVpnList.ClientSize.Width;

            foreach (var app in apps)
            {
                var row = new Panel
                {
                    Location = new Point(0, rowY),
                    Size = new Size(rowW, 44),
                    BackColor = app.IsActive ? Color.FromArgb(28, 38, 60) : Color.Transparent,
                };

                int textX = 8;
                if (app.IsActive)
                {
                    var dot = new Dot
                    {
                        Location = new Point(8, 16),
                        Size = new Size(10, 10),
                        DotColor = Theme.On
                    };
                    row.Controls.Add(dot);
                    textX = 24;
                }

                var lblName = MakeLabel(app.DisplayName, Theme.UI(9.5f, FontStyle.Bold), app.IsActive ? Theme.On : Theme.Text, textX, 4, rowW - textX - 70);
                row.Controls.Add(lblName);

                string subText = app.IsActive ? $"{app.Package}  •  Active VPN" : app.Package;
                var lblPkg = MakeLabel(subText, Theme.UI(8f), app.IsActive ? Theme.Secondary : Theme.Muted, textX, 23, rowW - textX - 70);
                row.Controls.Add(lblPkg);

                var btnOpen = new Button
                {
                    Text = "Open",
                    Location = new Point(rowW - 64, 8),
                    Size = new Size(58, 28),
                    FlatStyle = FlatStyle.Flat,
                    BackColor = app.IsActive ? Theme.Start : Theme.Stroke,
                    ForeColor = Color.White,
                    Font = Theme.UI(8.5f, FontStyle.Bold),
                    Cursor = Cursors.Hand,
                };
                btnOpen.FlatAppearance.BorderSize = 0;
                string pkg = app.Package;
                string label = app.DisplayName;
                btnOpen.Click += (s, e) =>
                {
                    try
                    {
                        Scrcpy.Start(SelectedSerial, pkg, label);
                    }
                    catch (Exception ex)
                    {
                        MessageBox.Show("Could not start scrcpy: " + ex.Message, "PdaNext", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    }
                };
                row.Controls.Add(btnOpen);

                pnlVpnList.Controls.Add(row);
                rowY += 46;
            }

            pnlVpnList.ResumeLayout();
        }

        /// <summary>Real ICMP pings through the adapter: to the phone itself, and to the internet via the phone.</summary>
        async System.Threading.Tasks.Task PingOnce()
        {
            string router = bridge.RouterAddress;
            if (pinging || bridge.State != BridgeState.Connected || router == null || !Visible)
            {
                if (bridge.State != BridgeState.Connected) lblRouterPing.Text = lblNetPing.Text = "—";
                return;
            }
            pinging = true;
            try
            {
                lblRouterPing.Text = await PingText(router);
                lblNetPing.Text = await PingText("1.1.1.1");
            }
            finally { pinging = false; }
        }

        static async System.Threading.Tasks.Task<string> PingText(string host)
        {
            try
            {
                using (var p = new Ping())
                {
                    var reply = await p.SendPingAsync(host, 3000);
                    return reply.Status == IPStatus.Success ? reply.RoundtripTime + " ms" : "timeout";
                }
            }
            catch { return "—"; }
        }

        void ToggleLog()
        {
            txtLog.Visible = !txtLog.Visible;
            lnkLog.Text = txtLog.Visible ? "Hide log" : "Show log";
            ClientSize = new Size(ClientSize.Width, txtLog.Visible ? 920 + 160 : 920);
        }

        async System.Threading.Tasks.Task SaveDiagnosticsAsync()
        {
            using (var dlg = new SaveFileDialog())
            {
                dlg.Title = "Save Diagnostics";
                dlg.Filter = "Zip Archive (*.zip)|*.zip";
                dlg.FileName = "pdanext-diagnostics-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".zip";
                if (dlg.ShowDialog(this) != DialogResult.OK) return;

                btnSaveDiag.Enabled = false;
                string prevText = btnSaveDiag.Text;
                btnSaveDiag.Text = "Saving…";
                try
                {
                    string target = dlg.FileName;
                    int port = bridge.Port;
                    string cachedStatus = bridge.LastStatus?.RawJson ?? lastStatus?.RawJson;
                    await System.Threading.Tasks.Task.Run(() => Diagnostics.Collect(target, port, cachedStatus));
                    MessageBox.Show(this, "Diagnostics saved to:\n" + target, "Diagnostics Saved", MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
                catch (Exception ex)
                {
                    MessageBox.Show(this, "Failed to save diagnostics: " + ex.Message, "Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
                finally
                {
                    btnSaveDiag.Enabled = true;
                    btnSaveDiag.Text = prevText;
                }
            }
        }

        void ClearLogsClicked()
        {
            var confirm = MessageBox.Show(
                this,
                "Are you sure you want to delete all log files?",
                "Clear logs",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Question
            );
            if (confirm != DialogResult.Yes) return;

            int deleted = FileLogger.ClearLogs(FileLogger.LogDirectory);
            if (txtLog != null && txtLog.Visible) txtLog.Text = "";
            MessageBox.Show(
                this,
                $"Deleted {deleted} log file(s).",
                "Clear logs",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information
            );
        }

        void AppendLog(string line)
        {
            if (txtLog.TextLength > 200_000) txtLog.Text = txtLog.Text.Substring(100_000);
            txtLog.AppendText(line + Environment.NewLine);
        }

        static string Rate(double bytesPerSec) =>
            bytesPerSec >= 1024 * 1024 ? (bytesPerSec / (1024 * 1024)).ToString("0.0") + " MB/s" : (bytesPerSec / 1024).ToString("0") + " KB/s";

        static string FormatSize(long bytes) =>
            bytes >= 1L << 30 ? (bytes / (double)(1L << 30)).ToString("0.00") + " GB" : (bytes / (double)(1L << 20)).ToString("0.0") + " MB";

        void ShowFromTray()
        {
            Show();
            WindowState = FormWindowState.Normal;
            Activate();
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            // Closing the window keeps the connection alive in the tray; Exit is in the tray menu.
            if (!exiting && e.CloseReason == CloseReason.UserClosing && bridge.WantsConnection)
            {
                e.Cancel = true;
                Hide();
                if (!trayHintShown)
                {
                    trayHintShown = true;
                    tray.ShowBalloonTip(3000, "PdaNext is still running", "Right-click the tray icon to disconnect or exit.", ToolTipIcon.Info);
                }
                return;
            }
            base.OnFormClosing(e);
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            tray.Visible = false;
            tray.Dispose();
            Scrcpy.Stop();
            base.OnFormClosed(e);
        }

        void ExitApp()
        {
            exiting = true;
            Close();
        }

        void Ui(Action a)
        {
            if (IsDisposed || !IsHandleCreated) return;
            try { BeginInvoke(a); } catch (InvalidOperationException) { }
        }
    }
}
