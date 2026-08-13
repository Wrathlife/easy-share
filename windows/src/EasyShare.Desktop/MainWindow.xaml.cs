using System.ComponentModel;
using System.IO;
using System.Runtime.CompilerServices;
using System.Windows;
using EasyShare.Protocol;
using Microsoft.Win32;

namespace EasyShare.Desktop;

public partial class MainWindow : Window
{
    private InternetSession? _session;
    private readonly MainVm _vm = new();
    private List<LocalShareEntry> _shareEntries = new();
    private string? _receiveFolder;
    private bool _isHostFlow;

    public MainWindow()
    {
        InitializeComponent();
        DataContext = _vm;
        ShowHome();
    }

    private void ShowHome()
    {
        HomePanel.Visibility = Visibility.Visible;
        SharePanel.Visibility = Visibility.Collapsed;
        ReceivePanel.Visibility = Visibility.Collapsed;
        _vm.ResetSessionUi();
        _vm.Status = "";
        _isHostFlow = false;
    }

    private void OnShareClick(object sender, RoutedEventArgs e)
    {
        HomePanel.Visibility = Visibility.Collapsed;
        SharePanel.Visibility = Visibility.Visible;
        ReceivePanel.Visibility = Visibility.Collapsed;
        _isHostFlow = true;
        _vm.ResetSessionUi();
        _vm.Status = "Pick files to share.";
        _vm.FilesSummary = _shareEntries.Count == 0
            ? "No files selected yet."
            : $"{_shareEntries.Count} file(s) selected.";
    }

    private void OnReceiveClick(object sender, RoutedEventArgs e)
    {
        HomePanel.Visibility = Visibility.Collapsed;
        SharePanel.Visibility = Visibility.Collapsed;
        ReceivePanel.Visibility = Visibility.Visible;
        _isHostFlow = false;
        _vm.ResetSessionUi();
        _vm.Status = "Enter the share code from the other device.";
    }

    private void OnBackHome(object sender, RoutedEventArgs e)
    {
        _ = StopSessionAsync();
        ShowHome();
    }

    private void OnPickFiles(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Multiselect = true,
            Title = "Choose files to share"
        };
        if (dlg.ShowDialog() != true) return;
        _shareEntries = dlg.FileNames.Select(path =>
        {
            var info = new FileInfo(path);
            return new LocalShareEntry(path, info.Name, info.Length);
        }).ToList();
        _vm.FilesSummary = $"{_shareEntries.Count} file(s) selected · {FormatBytes(_shareEntries.Sum(f => f.SizeBytes))}";
        _vm.Status = "Ready to share.";
    }

    private async void OnStartShare(object sender, RoutedEventArgs e)
    {
        if (_shareEntries.Count == 0)
        {
            _vm.Status = "Pick at least one file first.";
            return;
        }
        await StopSessionAsync();
        var code = PairingCode.GenerateShort();
        _vm.CodeDisplay = PairingCode.FormatForDisplay(code);
        _vm.ShowCodeCard = true;
        _vm.ShowConfirmCard = false;
        _vm.Status = "Connecting…";
        _vm.CanConfirm = false;
        _session = WireSession();
        var files = _shareEntries.Select(f => new SharedFileInfo(f.RelativePath, f.SizeBytes)).ToList();
        await _session.StartHostAsync(code, files);
    }

    private async void OnStartReceive(object sender, RoutedEventArgs e)
    {
        var code = PairingCode.Normalize(ReceiveCodeBox.Text ?? "");
        if (!PairingCode.IsValidShort(code))
        {
            _vm.Status = "Invalid share code format.";
            return;
        }
        var folderDlg = new OpenFolderDialog { Title = "Choose folder to save received files" };
        if (folderDlg.ShowDialog() != true) return;
        _receiveFolder = folderDlg.FolderName;
        await StopSessionAsync();
        _vm.ShowConfirmCard = false;
        _vm.Status = "Connecting…";
        _vm.CanConfirm = false;
        _session = WireSession();
        await _session.StartGuestAsync(code);
    }

    private void OnConfirm(object sender, RoutedEventArgs e) => _session?.ConfirmLocalPairing();

    private void OnReject(object sender, RoutedEventArgs e) => _session?.RejectLocalPairing();

    private InternetSession WireSession()
    {
        var session = new InternetSession();
        session.StateChanged += state => Dispatcher.Invoke(() => OnState(state));
        session.RemoteFilesChanged += files => Dispatcher.Invoke(() =>
        {
            if (files.Count > 0)
                _vm.Status = $"Incoming: {files.Count} file(s), {FormatBytes(files.Sum(f => Math.Max(0, f.SizeBytes)))}";
        });
        session.ProgressChanged += p => Dispatcher.Invoke(() =>
        {
            if (p is null) { _vm.ProgressText = ""; return; }
            var pct = p.BytesTotal > 0 ? (100.0 * p.BytesDone / p.BytesTotal) : 0;
            _vm.ProgressValue = pct;
            var speed = p.SpeedBytesPerSec > 0 ? $"{FormatBytes(p.SpeedBytesPerSec)}/s" : "";
            var file = p.CurrentFileName is not null ? $" · {p.CurrentFileName}" : "";
            _vm.ProgressText = $"{pct:0.#}%  {FormatBytes(p.BytesDone)} / {FormatBytes(p.BytesTotal)}  {speed}{file}";
        });
        session.TransferCompleted += () => Dispatcher.Invoke(() =>
        {
            _vm.Status = "Transfer finished successfully.";
            _vm.ProgressValue = 100;
            _vm.ShowConfirmCard = false;
        });
        session.TransferFailed += reason => Dispatcher.Invoke(() => _vm.Status = "Failed: " + reason);
        session.SavedFilesChanged += files => Dispatcher.Invoke(() =>
        {
            if (files.Count > 0)
                _vm.Status = $"Saved {files.Count} file(s).";
        });
        return session;
    }

    private async void OnState(PairingState state)
    {
        switch (state)
        {
            case PairingState.Connecting:
                _vm.Status = "Connecting…";
                break;
            case PairingState.Waiting:
                _vm.Status = _isHostFlow
                    ? "Waiting for the other device… Enter this code there."
                    : "Waiting for the sharer…";
                break;
            case PairingState.Confirming c:
                _vm.Phrase = c.Phrase;
                _vm.ShowConfirmCard = true;
                _vm.CanConfirm = !c.LocalConfirmed;
                _vm.ConfirmButtonText = c.LocalConfirmed ? "You confirmed" : "Yes — words match";
                _vm.ConfirmHint = (c.LocalConfirmed, c.PeerConfirmed) switch
                {
                    (true, true) => "Both confirmed",
                    (true, false) => "Waiting for the other device to confirm…",
                    (false, true) => "Other device confirmed — your turn",
                    _ => "Neither device has confirmed yet"
                };
                _vm.Status = "Confirm these are the right devices";
                break;
            case PairingState.Paired:
                _vm.CanConfirm = false;
                _vm.ShowConfirmCard = false;
                _vm.Status = "Paired — starting transfer…";
                await BeginTransferAfterPairedAsync();
                break;
            case PairingState.Failed f:
                _vm.Status = f.Reason;
                _vm.CanConfirm = false;
                _vm.ShowConfirmCard = false;
                break;
        }
    }

    private async Task BeginTransferAfterPairedAsync()
    {
        if (_session is null) return;
        var encrypt = _vm.EncryptEnabled;
        if (_isHostFlow && _shareEntries.Count > 0)
        {
            await _session.StartHostFileTransferAsync(_shareEntries, encrypt);
        }
        else if (_receiveFolder is not null)
        {
            var expected = _session.RemoteFiles.ToList();
            await _session.PrepareGuestFileSinkAsync(_receiveFolder, expected, encrypt, beginTransfer: true);
        }
    }

    private async Task StopSessionAsync()
    {
        if (_session is not null)
        {
            await _session.StopAsync();
            await _session.DisposeAsync();
            _session = null;
        }
    }

    private static string FormatBytes(long n)
    {
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        double v = Math.Max(0, n);
        var i = 0;
        while (v >= 1024 && i < units.Length - 1) { v /= 1024; i++; }
        return $"{v:0.##} {units[i]}";
    }

    protected override async void OnClosed(EventArgs e)
    {
        await StopSessionAsync();
        base.OnClosed(e);
    }
}

public sealed class MainVm : INotifyPropertyChanged
{
    private string _status = "";
    private string _codeDisplay = "";
    private string _phrase = "";
    private string _progressText = "";
    private string _filesSummary = "No files selected yet.";
    private string _confirmHint = "";
    private string _confirmButtonText = "Yes — words match";
    private double _progressValue;
    private bool _canConfirm;
    private bool _encryptEnabled;
    private bool _showCodeCard;
    private bool _showConfirmCard;

    public event PropertyChangedEventHandler? PropertyChanged;

    public string HomeSubtitle { get; } =
        "Share files over the internet with a short code. Pairing is online; file bytes stay peer-to-peer (not relayed).";

    public string Status { get => _status; set => Set(ref _status, value); }
    public string CodeDisplay { get => _codeDisplay; set => Set(ref _codeDisplay, value); }
    public string Phrase { get => _phrase; set => Set(ref _phrase, value); }
    public string ProgressText { get => _progressText; set => Set(ref _progressText, value); }
    public string FilesSummary { get => _filesSummary; set => Set(ref _filesSummary, value); }
    public string ConfirmHint { get => _confirmHint; set => Set(ref _confirmHint, value); }
    public string ConfirmButtonText { get => _confirmButtonText; set => Set(ref _confirmButtonText, value); }
    public double ProgressValue { get => _progressValue; set => Set(ref _progressValue, value); }
    public bool CanConfirm { get => _canConfirm; set => Set(ref _canConfirm, value); }
    public bool EncryptEnabled { get => _encryptEnabled; set => Set(ref _encryptEnabled, value); }
    public bool ShowCodeCard { get => _showCodeCard; set => Set(ref _showCodeCard, value); }
    public bool ShowConfirmCard { get => _showConfirmCard; set => Set(ref _showConfirmCard, value); }

    public void ResetSessionUi()
    {
        CodeDisplay = "";
        Phrase = "";
        ProgressText = "";
        ProgressValue = 0;
        CanConfirm = false;
        ShowCodeCard = false;
        ShowConfirmCard = false;
        ConfirmHint = "";
        ConfirmButtonText = "Yes — words match";
    }

    private void Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (Equals(field, value)) return;
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}
