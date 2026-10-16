using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace PdaNext
{
    public sealed class ServersForm : Form
    {
        readonly int port;
        readonly ListView lstServers;
        readonly Button btnUse, btnOff, btnImport, btnDelete, btnDelay, btnDelayAll, btnSaveBypass;
        readonly TextBox txtBypass;
        readonly Label lblStatus;
        List<XrayServerProfile> profiles = new List<XrayServerProfile>();
        bool busy;

        public ServersForm(int port)
        {
            this.port = port;

            Text = "Xray Servers - PdaNext";
            Icon = Program.AppIcon;
            BackColor = Theme.Bg;
            ForeColor = Theme.Text;
            Font = Theme.UI(9.5f);
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            StartPosition = FormStartPosition.CenterParent;
            ClientSize = new Size(540, 620);

            int x = 16, w = ClientSize.Width - 32, y = 16;

            var lblTitle = new Label
            {
                Text = "Xray Servers",
                Font = Theme.UI(14f, FontStyle.Bold),
                ForeColor = Theme.Text,
                Location = new Point(x, y),
                AutoSize = true,
            };
            Controls.Add(lblTitle);
            y += 32;

            // Servers list
            lstServers = new ListView
            {
                Location = new Point(x, y),
                Size = new Size(w, 200),
                View = View.Details,
                FullRowSelect = true,
                MultiSelect = false,
                HeaderStyle = ColumnHeaderStyle.Nonclickable,
                BackColor = Theme.Card,
                ForeColor = Theme.Text,
                BorderStyle = BorderStyle.FixedSingle,
                Font = Theme.UI(9.5f),
            };
            lstServers.Columns.Add("Server Name", 280);
            lstServers.Columns.Add("Status", 110);
            lstServers.Columns.Add("Delay", 100);
            lstServers.SelectedIndexChanged += (s, e) => UpdateButtonStates();
            Controls.Add(lstServers);
            y += 208;

            // Server action buttons
            int btnW = (w - 30) / 4;
            btnUse = MakeButton("Use", x, y, btnW, 30, Theme.Start, () => UseSelected());
            btnOff = MakeButton("Off (direct)", x + btnW + 10, y, btnW, 30, Theme.Stroke, () => TurnOff());
            btnImport = MakeButton("Import", x + (btnW + 10) * 2, y, btnW, 30, Theme.Stroke, () => ImportFromClipboard());
            btnDelete = MakeButton("Delete", x + (btnW + 10) * 3, y, btnW, 30, Theme.Stop, () => DeleteSelected());
            Controls.AddRange(new Control[] { btnUse, btnOff, btnImport, btnDelete });
            y += 38;

            // Delay buttons
            int delayW = (w - 10) / 2;
            btnDelay = MakeButton("Test delay", x, y, delayW, 30, Theme.Stroke, () => TestSelectedDelay());
            btnDelayAll = MakeButton("Test all", x + delayW + 10, y, delayW, 30, Theme.Stroke, () => TestAllDelay());
            Controls.AddRange(new Control[] { btnDelay, btnDelayAll });
            y += 46;

            // Bypass section
            var lblBypass = new Label
            {
                Text = "Iranian Domestic Domains (Bypassed from tunnel, one per line):",
                Font = Theme.UI(9f, FontStyle.Bold),
                ForeColor = Theme.Secondary,
                Location = new Point(x, y),
                AutoSize = true,
            };
            Controls.Add(lblBypass);
            y += 24;

            txtBypass = new TextBox
            {
                Multiline = true,
                ScrollBars = ScrollBars.Vertical,
                Location = new Point(x, y),
                Size = new Size(w, 140),
                BackColor = Theme.Card,
                ForeColor = Theme.Text,
                BorderStyle = BorderStyle.FixedSingle,
                Font = new Font("Consolas", 9f),
            };
            Controls.Add(txtBypass);
            y += 148;

            btnSaveBypass = MakeButton("Save domains", x, y, 140, 30, Theme.Stroke, () => SaveBypassDomains());
            Controls.Add(btnSaveBypass);

            lblStatus = new Label
            {
                Text = "Loading…",
                Font = Theme.UI(9f),
                ForeColor = Theme.Muted,
                Location = new Point(x + 150, y + 6),
                AutoSize = true,
            };
            Controls.Add(lblStatus);

            Shown += async (s, e) => await RefreshAll();
        }

        Button MakeButton(string text, int bx, int by, int bw, int bh, Color bg, Action onClick)
        {
            var btn = new Button
            {
                Text = text,
                Location = new Point(bx, by),
                Size = new Size(bw, bh),
                FlatStyle = FlatStyle.Flat,
                BackColor = bg,
                ForeColor = Color.White,
                Font = Theme.UI(9f, FontStyle.Bold),
                Cursor = Cursors.Hand,
            };
            btn.FlatAppearance.BorderSize = 0;
            btn.Click += (s, e) => onClick();
            return btn;
        }

        XrayServerProfile SelectedProfile
        {
            get
            {
                if (lstServers.SelectedIndices.Count == 0) return null;
                int idx = lstServers.SelectedIndices[0];
                return idx >= 0 && idx < profiles.Count ? profiles[idx] : null;
            }
        }

        void UpdateButtonStates()
        {
            var p = SelectedProfile;
            btnUse.Enabled = !busy && p != null && !p.Selected;
            btnDelete.Enabled = !busy && p != null;
            btnDelay.Enabled = !busy && p != null;
            btnOff.Enabled = !busy && profiles.Any(x => x.Selected);
            btnImport.Enabled = !busy;
            btnDelayAll.Enabled = !busy && profiles.Count > 0;
            btnSaveBypass.Enabled = !busy;
        }

        async Task RefreshAll()
        {
            if (busy) return;
            busy = true;
            lblStatus.Text = "Loading servers…";
            UpdateButtonStates();

            try
            {
                var list = await Task.Run(() => XrayClient.GetProfiles(port));
                var bypass = await Task.Run(() => XrayClient.GetBypassDomains(port));

                profiles = list;
                RenderList();
                txtBypass.Text = string.Join(Environment.NewLine, bypass);
                lblStatus.Text = $"{profiles.Count} server(s) loaded.";
            }
            catch (Exception ex)
            {
                lblStatus.Text = "Failed to load: " + ex.Message;
            }
            finally
            {
                busy = false;
                UpdateButtonStates();
            }
        }

        void RenderList()
        {
            lstServers.BeginUpdate();
            lstServers.Items.Clear();
            foreach (var p in profiles)
            {
                var item = new ListViewItem(p.Name);
                item.SubItems.Add(p.Selected ? "✔ Active" : "");
                item.SubItems.Add(p.DelayMs.HasValue ? $"{p.DelayMs.Value} ms" : "—");
                if (p.Selected)
                {
                    item.ForeColor = Theme.On;
                }
                lstServers.Items.Add(item);
            }
            lstServers.EndUpdate();
        }

        async void UseSelected()
        {
            var p = SelectedProfile;
            if (p == null || busy) return;
            busy = true;
            lblStatus.Text = $"Selecting {p.Name}…";
            UpdateButtonStates();

            try
            {
                await Task.Run(() => XrayClient.Select(port, p.Id));
                lblStatus.Text = $"Selected {p.Name}.";
                await RefreshProfilesOnly();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, "Could not select server: " + ex.Message, "PdaNext", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                lblStatus.Text = "Selection failed.";
            }
            finally
            {
                busy = false;
                UpdateButtonStates();
            }
        }

        async void TurnOff()
        {
            if (busy) return;
            busy = true;
            lblStatus.Text = "Turning off tunnel (direct mode)…";
            UpdateButtonStates();

            try
            {
                await Task.Run(() => XrayClient.Select(port, null));
                lblStatus.Text = "Tunnel is off (direct).";
                await RefreshProfilesOnly();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, "Could not turn off tunnel: " + ex.Message, "PdaNext", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
            finally
            {
                busy = false;
                UpdateButtonStates();
            }
        }

        async void ImportFromClipboard()
        {
            if (busy) return;
            string text = Clipboard.GetText();
            if (string.IsNullOrWhiteSpace(text))
            {
                MessageBox.Show(this, "Clipboard does not contain any text.", "PdaNext", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }

            busy = true;
            lblStatus.Text = "Importing from clipboard…";
            UpdateButtonStates();

            try
            {
                var added = await Task.Run(() => XrayClient.ImportLinks(port, text));
                if (added.Count > 0)
                {
                    lblStatus.Text = $"Imported {added.Count} server(s).";
                }
                else
                {
                    lblStatus.Text = "No valid server links found in clipboard.";
                    MessageBox.Show(this, "No valid server links (vless://, vmess://, etc.) found in clipboard.", "PdaNext", MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
                await RefreshProfilesOnly();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, "Could not import: " + ex.Message, "PdaNext", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                lblStatus.Text = "Import failed.";
            }
            finally
            {
                busy = false;
                UpdateButtonStates();
            }
        }

        async void DeleteSelected()
        {
            var p = SelectedProfile;
            if (p == null || busy) return;

            var confirm = MessageBox.Show(this, $"Are you sure you want to delete server '{p.Name}'?", "Confirm Delete", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            if (confirm != DialogResult.Yes) return;

            busy = true;
            lblStatus.Text = $"Deleting {p.Name}…";
            UpdateButtonStates();

            try
            {
                await Task.Run(() => XrayClient.Delete(port, p.Id));
                lblStatus.Text = $"Deleted {p.Name}.";
                await RefreshProfilesOnly();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, "Could not delete: " + ex.Message, "PdaNext", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
            finally
            {
                busy = false;
                UpdateButtonStates();
            }
        }

        async void TestSelectedDelay()
        {
            var p = SelectedProfile;
            if (p == null || busy) return;

            busy = true;
            lblStatus.Text = $"Testing delay for {p.Name}…";
            UpdateButtonStates();

            try
            {
                long? ms = await Task.Run(() => XrayClient.MeasureDelay(port, p.Id));
                p.DelayMs = ms;
                RenderList();
                lblStatus.Text = ms.HasValue ? $"{p.Name}: {ms.Value} ms" : $"{p.Name}: Timeout / unreachable";
            }
            catch (Exception ex)
            {
                lblStatus.Text = "Delay test failed: " + ex.Message;
            }
            finally
            {
                busy = false;
                UpdateButtonStates();
            }
        }

        async void TestAllDelay()
        {
            if (busy || profiles.Count == 0) return;
            busy = true;
            lblStatus.Text = "Testing delays…";
            UpdateButtonStates();

            try
            {
                for (int i = 0; i < profiles.Count; i++)
                {
                    var p = profiles[i];
                    lblStatus.Text = $"Testing {i + 1}/{profiles.Count}: {p.Name}…";
                    long? ms = await Task.Run(() => XrayClient.MeasureDelay(port, p.Id));
                    p.DelayMs = ms;
                    RenderList();
                }
                lblStatus.Text = "Delay testing finished.";
            }
            catch (Exception ex)
            {
                lblStatus.Text = "Delay test interrupted: " + ex.Message;
            }
            finally
            {
                busy = false;
                UpdateButtonStates();
            }
        }

        async void SaveBypassDomains()
        {
            if (busy) return;
            busy = true;
            lblStatus.Text = "Saving bypass domains…";
            UpdateButtonStates();

            var lines = txtBypass.Lines
                .Select(l => l.Trim())
                .Where(l => !string.IsNullOrEmpty(l))
                .ToList();

            try
            {
                var updated = await Task.Run(() => XrayClient.SetBypassDomains(port, lines));
                txtBypass.Text = string.Join(Environment.NewLine, updated);
                lblStatus.Text = $"Saved {updated.Count} bypass domain(s).";
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, "Could not save bypass domains: " + ex.Message, "PdaNext", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                lblStatus.Text = "Save bypass domains failed.";
            }
            finally
            {
                busy = false;
                UpdateButtonStates();
            }
        }

        async Task RefreshProfilesOnly()
        {
            var list = await Task.Run(() => XrayClient.GetProfiles(port));
            // Preserve measured delays
            var delays = profiles.Where(p => p.DelayMs.HasValue).ToDictionary(p => p.Id, p => p.DelayMs);
            foreach (var p in list)
            {
                if (delays.TryGetValue(p.Id, out var ms)) p.DelayMs = ms;
            }
            profiles = list;
            RenderList();
        }
    }
}
