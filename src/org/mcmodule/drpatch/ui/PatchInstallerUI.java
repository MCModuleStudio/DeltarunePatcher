package org.mcmodule.drpatch.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.mcmodule.drpatch.DeltarunePatcher;
import org.mcmodule.drpatch.patchsrc.PatchSource;
import org.mcmodule.drpatch.util.EnumOS;
import org.mcmodule.drpatch.util.EnumPatchType;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public class PatchInstallerUI extends JFrame {
    private final PatchDownloadSource[] downloadSources = { new GitHubReleaseClient() };
    private final Path workingDirectory = Paths.get(System.getProperty("user.dir"));
    private final JComboBox<String> patchComboBox = new JComboBox<>();
    private final JTextField gamePathField = new JTextField();
    private final JLabel progressTextLabel = new JLabel("准备就绪");
    private final JTextArea logTextArea = new JTextArea(5, 20);
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JButton downloadButton = new JButton("下载");
    private final JButton installButton = new JButton("安装补丁");
    private final JButton restoreButton = new JButton("恢复备份");
    private boolean taskRunning;
    private WatchService watchService;
    private Thread watchThread;

    public PatchInstallerUI() {
        super("补丁安装器");
        initLookAndFeel();
        initComponents();
        refreshPatchFiles();
        startPatchFileWatcher();
    }

    private static void initLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
    }

    private void initComponents() {
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(680, 360));
        setLocationRelativeTo(null);

        patchComboBox.setEditable(true);
        logTextArea.setEditable(false);
        logTextArea.setLineWrap(true);
        logTextArea.setWrapStyleWord(true);
        progressBar.setStringPainted(true);

        JButton patchBrowseButton = new JButton("浏览");
        JButton gameBrowseButton = new JButton("浏览");

        patchBrowseButton.addActionListener(e -> choosePatchFile());
        gameBrowseButton.addActionListener(e -> chooseGamePath());
        downloadButton.addActionListener(e -> chooseDownloadSource());
        installButton.addActionListener(e -> installPatch());
        restoreButton.addActionListener(e -> restoreBackup());

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        formPanel.add(new JLabel("补丁文件:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 1;
        formPanel.add(patchComboBox, gbc);

        gbc.gridx = 2;
        gbc.gridy = 0;
        gbc.weightx = 0;
        formPanel.add(patchBrowseButton, gbc);

        gbc.gridx = 3;
        gbc.gridy = 0;
        formPanel.add(downloadButton, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        formPanel.add(new JLabel("游戏路径:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        formPanel.add(gamePathField, gbc);

        gbc.gridx = 3;
        gbc.gridy = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        formPanel.add(gameBrowseButton, gbc);

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(installButton);
        buttonPanel.add(restoreButton);

        JScrollPane logScrollPane = new JScrollPane(logTextArea);
        logScrollPane.setBorder(BorderFactory.createEmptyBorder(8, 22, 8, 22));

        JPanel bottomPanel = new JPanel(new BorderLayout(0, 6));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(0, 22, 16, 22));
        bottomPanel.add(buttonPanel, BorderLayout.NORTH);
        bottomPanel.add(progressTextLabel, BorderLayout.CENTER);
        bottomPanel.add(progressBar, BorderLayout.SOUTH);

        add(formPanel, BorderLayout.NORTH);
        add(logScrollPane, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        installDragAndDropSupport();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                stopPatchFileWatcher();
            }
        });

        pack();
    }

    private void choosePatchFile() {
        JFileChooser chooser = new JFileChooser(workingDirectory.toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("7z 补丁文件", "7z"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String path = toPatchDisplayPath(chooser.getSelectedFile().toPath());
            addPatchOption(path);
            patchComboBox.setSelectedItem(path);
            appendLog("已选择补丁文件: " + path);
        }
    }

    private void chooseGamePath() {
        JFileChooser chooser = new JFileChooser(workingDirectory.toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            gamePathField.setText(chooser.getSelectedFile().getAbsolutePath());
            appendLog("已选择游戏路径: " + chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void installDragAndDropSupport() {
        TransferHandler transferHandler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                Transferable transferable = support.getTransferable();
                try {
                    Object data = transferable.getTransferData(DataFlavor.javaFileListFlavor);
                    if (!(data instanceof List<?> files)) {
                        return false;
                    }
                    boolean imported = false;
                    for (Object item : files) {
                        if (item instanceof File file && handleDroppedFile(file)) {
                            imported = true;
                        }
                    }
                    return imported;
                } catch (Exception e) {
                    appendLog("拖拽导入失败: " + e.getMessage());
                    return false;
                }
            }
        };
        applyTransferHandler(getRootPane(), transferHandler);
        applyTransferHandler(getContentPane(), transferHandler);
    }

    private void applyTransferHandler(Component component, TransferHandler transferHandler) {
        if (component instanceof JComponent jComponent) {
            jComponent.setTransferHandler(transferHandler);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyTransferHandler(child, transferHandler);
            }
        }
    }

    private boolean handleDroppedFile(File file) {
        if (file.isDirectory()) {
            gamePathField.setText(file.getAbsolutePath());
            appendLog("已拖入游戏路径: " + file.getAbsolutePath());
            return true;
        }
        if (file.isFile() && file.getName().toLowerCase().endsWith(".7z")) {
            String path = toPatchDisplayPath(file.toPath());
            addPatchOption(path);
            patchComboBox.setSelectedItem(path);
            appendLog("已拖入补丁文件: " + path);
            return true;
        }
        appendLog("已忽略拖入文件: " + file.getAbsolutePath());
        return false;
    }

    private void chooseDownloadSource() {
        if (taskRunning) {
            return;
        }
        Object source = JOptionPane.showInputDialog(this, "请选择安装源", "下载补丁", JOptionPane.QUESTION_MESSAGE, null,
                downloadSources, downloadSources[0]);
        if (!(source instanceof PatchDownloadSource downloadSource)) {
            return;
        }
        fetchReleases(downloadSource);
    }

    private void fetchReleases(PatchDownloadSource downloadSource) {
        taskRunning = true;
        setTaskButtonsEnabled(false);
        progressBar.setIndeterminate(true);
        progressTextLabel.setText("正在获取版本列表...");
        appendLog("正在从" + downloadSource.name() + "获取版本列表");

        SwingWorker<List<PatchDownloadSource.Release>, Void> worker = new SwingWorker<List<PatchDownloadSource.Release>, Void>() {
            @Override
            protected List<PatchDownloadSource.Release> doInBackground() throws Exception {
                return downloadSource.listReleases();
            }

            @Override
            protected void done() {
                progressBar.setIndeterminate(false);
                progressBar.setValue(0);
                taskRunning = false;
                setTaskButtonsEnabled(true);
                try {
                    List<PatchDownloadSource.Release> releases = get();
                    if (releases.isEmpty()) {
                        progressTextLabel.setText("未找到可下载版本");
                        JOptionPane.showMessageDialog(PatchInstallerUI.this, "未找到包含文件的 release", "提示", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    progressTextLabel.setText("请选择要下载的版本和文件");
                    showReleaseAssetDialog(downloadSource, releases);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showDownloadError("获取版本列表已中断", e);
                } catch (ExecutionException e) {
                    showDownloadError("获取版本列表失败", e.getCause());
                }
            }
        };
        worker.execute();
    }

    private void showReleaseAssetDialog(PatchDownloadSource downloadSource, List<PatchDownloadSource.Release> releases) {
        JComboBox<PatchDownloadSource.Release> releaseComboBox = new JComboBox<>(releases.toArray(new PatchDownloadSource.Release[0]));
        JComboBox<PatchDownloadSource.Asset> assetComboBox = new JComboBox<>();
        updateAssetComboBox(assetComboBox, (PatchDownloadSource.Release) releaseComboBox.getSelectedItem());
        releaseComboBox.addActionListener(e -> updateAssetComboBox(assetComboBox, (PatchDownloadSource.Release) releaseComboBox.getSelectedItem()));

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        panel.add(new JLabel("版本:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(releaseComboBox, gbc);
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        panel.add(new JLabel("文件:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(assetComboBox, gbc);

        int result = JOptionPane.showConfirmDialog(this, panel, "选择下载文件", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            progressTextLabel.setText("已取消下载");
            return;
        }
        PatchDownloadSource.Asset asset = (PatchDownloadSource.Asset) assetComboBox.getSelectedItem();
        if (asset == null) {
            JOptionPane.showMessageDialog(this, "请选择要下载的文件", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        downloadAsset(downloadSource, asset);
    }

    private void updateAssetComboBox(JComboBox<PatchDownloadSource.Asset> assetComboBox, PatchDownloadSource.Release release) {
        assetComboBox.removeAllItems();
        if (release == null) {
            return;
        }
        for (PatchDownloadSource.Asset asset : release.assets()) {
            assetComboBox.addItem(asset);
        }
    }

    private void downloadAsset(PatchDownloadSource downloadSource, PatchDownloadSource.Asset asset) {
        String fileName = sanitizeFileName(asset.name());
        Path target = workingDirectory.resolve(fileName);
        if (Files.exists(target)) {
            int result = JOptionPane.showConfirmDialog(this, "文件已存在，是否覆盖？\n" + target, "确认覆盖", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (result != JOptionPane.YES_OPTION) {
                progressTextLabel.setText("已取消下载");
                return;
            }
        }

        taskRunning = true;
        setTaskButtonsEnabled(false);
        progressBar.setValue(0);
        progressTextLabel.setText("下载中... 0%");
        appendLog("开始下载: " + asset.name());

        SwingWorker<Path, PatchDownloadSource.DownloadProgress> worker = new SwingWorker<Path, PatchDownloadSource.DownloadProgress>() {
            @Override
            protected Path doInBackground() throws Exception {
                return downloadSource.download(asset, target, progress -> {
                    setProgress(Math.max(0, Math.min(100, progress.percent())));
                    publish(progress);
                });
            }

            @Override
            protected void process(List<PatchDownloadSource.DownloadProgress> chunks) {
                PatchDownloadSource.DownloadProgress progress = chunks.get(chunks.size() - 1);
                progressBar.setValue(progress.percent());
                progressTextLabel.setText("下载中... " + progress.percent() + "% " + formatSpeed(progress.bytesPerSecond()));
            }

            @Override
            protected void done() {
                taskRunning = false;
                setTaskButtonsEnabled(true);
                try {
                    Path downloaded = get();
                    String displayPath = toPatchDisplayPath(downloaded);
                    addPatchOption(displayPath);
                    patchComboBox.setSelectedItem(displayPath);
                    progressBar.setValue(100);
                    progressTextLabel.setText("下载完成 100%");
                    appendLog("下载完成: " + downloaded);
                    refreshPatchFiles();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showDownloadError("下载已中断", e);
                } catch (ExecutionException e) {
                    showDownloadError("下载失败", e.getCause());
                }
            }
        };

        worker.execute();
    }

    private String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond >= 1024L * 1024L) {
            return String.format("%.2f MB/s", bytesPerSecond / 1024.0 / 1024.0);
        }
        if (bytesPerSecond >= 1024L) {
            return String.format("%.2f KB/s", bytesPerSecond / 1024.0);
        }
        return bytesPerSecond + " B/s";
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void showDownloadError(String message, Throwable throwable) {
        String detail = throwable == null || throwable.getMessage() == null ? "未知错误" : throwable.getMessage();
        progressTextLabel.setText(message + ": " + detail);
        appendLog(message + ": " + detail);
        JOptionPane.showMessageDialog(this, message + "\n" + detail, "下载", JOptionPane.ERROR_MESSAGE);
    }

    private void installPatch() {
        Object selectedPatch = patchComboBox.getSelectedItem();
        String patchPath = selectedPatch == null ? "" : selectedPatch.toString().trim();
        String gamePath = gamePathField.getText().trim();

        if (patchPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请选择补丁文件", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path patchFile = resolvePatchPath(patchPath);
        if (!Files.isRegularFile(patchFile)) {
            JOptionPane.showMessageDialog(this, "补丁文件不存在\n" + patchFile, "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path gameDirectory = validateGameDirectory(gamePath);
        if (gameDirectory == null) {
            return;
        }

        runInstallTask(gameDirectory.toFile(), patchFile.toFile());
    }

    private void restoreBackup() {
        Path gameDirectory = validateGameDirectory(gamePathField.getText().trim());
        if (gameDirectory == null) {
            return;
        }
        int result = JOptionPane.showConfirmDialog(this, "将从游戏目录下的 backup 文件夹恢复备份，是否继续？", "恢复备份", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        runRestoreTask(gameDirectory.toFile());
    }

    private Path validateGameDirectory(String gamePath) {
        if (gamePath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请选择游戏路径", "提示", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        Path gameDirectory = Paths.get(gamePath);
        if (!Files.isDirectory(gameDirectory)) {
            JOptionPane.showMessageDialog(this, "游戏路径必须是文件夹", "提示", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return gameDirectory;
    }

    private Path resolvePatchPath(String patchPath) {
        Path path = Paths.get(patchPath);
        if (!path.isAbsolute()) {
            path = workingDirectory.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private void runInstallTask(File gameDirectory, File patchFile) {
        if (taskRunning) {
            return;
        }
        taskRunning = true;
        setTaskButtonsEnabled(false);
        progressBar.setValue(0);
        progressTextLabel.setText("安装补丁中... 0%");
        appendLog("开始安装补丁: " + patchFile.getAbsolutePath());

        SwingWorker<Void, String> worker = new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() throws Exception {
                DeltarunePatcher patcher = new DeltarunePatcher(gameDirectory);
                EnumOS os = EnumOS.detect();
                EnumPatchType[] types = EnumPatchType.values();
                int patchedCount = 0;
                int failureCount = 0;
                try (PatchSource patchSource = PatchSource.from(patchFile)) {
                    if (patchSource == null) {
                        throw new IOException("不支持的补丁文件格式");
                    }
                    for (int i = 0; i < types.length; i++) {
                        EnumPatchType type = types[i];
                        publish("正在安装: " + type);
                        try {
                            if (patcher.patch(patchSource, type, os)) {
                                patchedCount++;
                                publish("已安装: " + type);
                            } else {
                                publish("跳过: " + type);
                            }
                        } catch (Exception e) {
                            failureCount++;
                            publish("安装失败: " + type + " - " + e.getMessage());
                            if (!askContinueAfterInstallFailure(type, e)) {
                                publish("用户选择停止安装，正在恢复之前的备份");
                                restoreInstalledBackups(patcher, os, types, i, true, message -> publish(message));
                                throw new IOException("安装已停止，已尝试恢复备份", e);
                            }
                        }
                        setProgress((i + 1) * 100 / types.length);
                    }
                }
                if (failureCount > 0) {
                    throw new IOException("部分补丁安装失败，请查看日志");
                }
                if (patchedCount == 0) {
                    throw new IOException("未找到可安装的补丁内容");
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String message : chunks) {
                    appendLog(message);
                }
            }

            @Override
            protected void done() {
                taskRunning = false;
                setTaskButtonsEnabled(true);
                progressBar.setValue(100);
                try {
                    get();
                    progressTextLabel.setText("补丁安装完成 100%");
                    appendLog("补丁安装完成");
                    JOptionPane.showMessageDialog(PatchInstallerUI.this, "补丁安装完成", "安装补丁", JOptionPane.INFORMATION_MESSAGE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showTaskError("安装补丁已中断", e);
                } catch (ExecutionException e) {
                    showTaskError("安装补丁失败", e.getCause());
                }
            }
        };
        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                int progress = (int) evt.getNewValue();
                progressBar.setValue(progress);
                progressTextLabel.setText("安装补丁中... " + progress + "%");
            }
        });
        worker.execute();
    }

    private boolean askContinueAfterInstallFailure(EnumPatchType type, Exception exception) throws Exception {
        final boolean[] continueInstall = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            String detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            int result = JOptionPane.showConfirmDialog(this,
                    "安装 " + type + " 失败：\n" + detail + "\n\n是否继续安装剩余内容？\n选择“否”将停止安装并恢复之前的备份。",
                    "安装失败", JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE);
            continueInstall[0] = result == JOptionPane.YES_OPTION;
        });
        return continueInstall[0];
    }

    private void restoreInstalledBackups(DeltarunePatcher patcher, EnumOS os, EnumPatchType[] types, int lastIndex, boolean includeLast,
            Consumer<String> logger) throws IOException {
        int start = includeLast ? lastIndex : lastIndex - 1;
        for (int i = start; i >= 0; i--) {
            EnumPatchType type = types[i];
            logger.accept("正在恢复: " + type);
            if (patcher.restore(type, os)) {
                logger.accept("已恢复: " + type);
            } else {
                logger.accept("跳过恢复: " + type);
            }
        }
    }

    private void runRestoreTask(File gameDirectory) {
        if (taskRunning) {
            return;
        }
        taskRunning = true;
        setTaskButtonsEnabled(false);
        progressBar.setValue(0);
        progressTextLabel.setText("恢复备份中... 0%");
        appendLog("开始恢复备份: " + gameDirectory.getAbsolutePath());

        SwingWorker<Void, String> worker = new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() throws Exception {
                DeltarunePatcher patcher = new DeltarunePatcher(gameDirectory);
                EnumOS os = EnumOS.detect();
                EnumPatchType[] types = EnumPatchType.values();
                int restoredCount = 0;
                int failureCount = 0;
                for (int i = 0; i < types.length; i++) {
                    EnumPatchType type = types[i];
                    publish("正在恢复: " + type);
                    try {
                        if (patcher.restore(type, os)) {
                            restoredCount++;
                            publish("已恢复: " + type);
                        } else {
                            publish("跳过: " + type);
                        }
                    } catch (Exception e) {
                        failureCount++;
                        publish("恢复失败: " + type + " - " + e.getMessage());
                    }
                    setProgress((i + 1) * 100 / types.length);
                }
                if (failureCount > 0) {
                    throw new IOException("部分备份恢复失败，请查看日志");
                }
                if (restoredCount == 0) {
                    throw new IOException("未找到可恢复的备份");
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String message : chunks) {
                    appendLog(message);
                }
            }

            @Override
            protected void done() {
                taskRunning = false;
                setTaskButtonsEnabled(true);
                progressBar.setValue(100);
                try {
                    get();
                    progressTextLabel.setText("备份恢复完成 100%");
                    appendLog("备份恢复完成");
                    JOptionPane.showMessageDialog(PatchInstallerUI.this, "备份恢复完成", "恢复备份", JOptionPane.INFORMATION_MESSAGE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showTaskError("恢复备份已中断", e);
                } catch (ExecutionException e) {
                    showTaskError("恢复备份失败", e.getCause());
                }
            }
        };
        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                int progress = (int) evt.getNewValue();
                progressBar.setValue(progress);
                progressTextLabel.setText("恢复备份中... " + progress + "%");
            }
        });
        worker.execute();
    }

    private void showTaskError(String message, Throwable throwable) {
        String detail = throwable == null || throwable.getMessage() == null ? "未知错误" : throwable.getMessage();
        progressTextLabel.setText(message + ": " + detail);
        appendLog(message + ": " + detail);
        JOptionPane.showMessageDialog(this, message + "\n" + detail, "错误", JOptionPane.ERROR_MESSAGE);
    }

    private void setTaskButtonsEnabled(boolean enabled) {
        downloadButton.setEnabled(enabled);
        installButton.setEnabled(enabled);
        restoreButton.setEnabled(enabled);
    }

    private void appendLog(String message) {
        logTextArea.append(message + System.lineSeparator());
        logTextArea.setCaretPosition(logTextArea.getDocument().getLength());
    }

    private void refreshPatchFiles() {
        SwingUtilities.invokeLater(() -> {
            Object selected = patchComboBox.getSelectedItem();
            Set<String> patches = findPatchFiles();
            patchComboBox.removeAllItems();
            for (String patch : patches) {
                patchComboBox.addItem(patch);
            }
            if (selected != null && !selected.toString().trim().isEmpty()) {
                addPatchOption(selected.toString());
                patchComboBox.setSelectedItem(selected);
            } else if (patchComboBox.getItemCount() > 0) {
                patchComboBox.setSelectedIndex(0);
            }
            appendLog("已刷新补丁列表，找到 " + patches.size() + " 个补丁文件");
        });
    }

    private String toPatchDisplayPath(Path patchPath) {
        Path absolutePatchPath = patchPath.toAbsolutePath().normalize();
        Path absoluteWorkingDirectory = workingDirectory.toAbsolutePath().normalize();
        if (absolutePatchPath.getParent() != null && absolutePatchPath.getParent().equals(absoluteWorkingDirectory)) {
            return absolutePatchPath.getFileName().toString();
        }
        return absolutePatchPath.toString();
    }

    private Set<String> findPatchFiles() {
        Set<String> patches = new LinkedHashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(workingDirectory, "patch_*.7z")) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    patches.add(path.getFileName().toString());
                }
            }
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> {
                progressTextLabel.setText("扫描补丁文件失败: " + e.getMessage());
                appendLog("扫描补丁文件失败: " + e.getMessage());
            });
        }
        return patches;
    }

    private void addPatchOption(String patchPath) {
        for (int i = 0; i < patchComboBox.getItemCount(); i++) {
            if (patchPath.equals(patchComboBox.getItemAt(i))) {
                return;
            }
        }
        patchComboBox.addItem(patchPath);
    }

    private void startPatchFileWatcher() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            workingDirectory.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY
            );
        } catch (IOException e) {
            progressTextLabel.setText("监听目录失败: " + e.getMessage());
            appendLog("监听目录失败: " + e.getMessage());
            return;
        }

        watchThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    WatchKey key = watchService.take();
                    boolean shouldRefresh = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = (Path) event.context();
                        String fileName = changed.getFileName().toString();
                        if (fileName.startsWith("patch_") && fileName.endsWith(".7z")) {
                            shouldRefresh = true;
                        }
                    }
                    if (shouldRefresh) {
                        Timer timer = new Timer(200, e -> refreshPatchFiles());
                        timer.setRepeats(false);
                        timer.start();
                    }
                    if (!key.reset()) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ClosedWatchServiceException e) {
                    break;
                }
            }
        }, "patch-file-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private void stopPatchFileWatcher() {
        if (watchThread != null) {
            watchThread.interrupt();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static void main(String[] args) {
        if (System.getProperty("java.home") == null) { // Fix graalvm
            System.setProperty("java.home", ".");
        }
        SwingUtilities.invokeLater(() -> new PatchInstallerUI().setVisible(true));
    }

    static {
        initLookAndFeel();
    }
}
