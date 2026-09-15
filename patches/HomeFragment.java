package com.kurdish.roleplay.launcher;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kurdish.roleplay.R;
import com.kurdish.roleplay.game.SAMP;
import com.kurdish.roleplay.launcher.util.GameDataImporter;
import com.kurdish.roleplay.launcher.util.GameStorage;
import com.kurdish.roleplay.launcher.util.RemoteConfigManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HomeFragment extends Fragment {

    private ActivityResultLauncher<Uri> gtaFolderPicker;
    private ProgressDialog importDialog;

    private static final String[][] SAMP_BOOTSTRAP_FILES = new String[][] {
            {"data/script/mainv1.scm", "main.scm"},
            {"data/script/scriptv1.img", "script.img"},
            {"data/peds.ide", "peds.ide"},
            {"data/vehicles.ide", "vehicles.ide"},
            {"data/gta.dat", "gta.dat"},
            {"data/handling.cfg", "handling.cfg"},
            {"data/weapon.dat", "weapon.dat"},
            {"Fonts/HELVETICANEUELT-MEDIUMCOND.TTF", "fonts/arial_bold.ttf"}
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        gtaFolderPicker = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
            if (uri == null || getContext() == null) return;
            try {
                requireContext().getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Throwable ignored) { }
            beginImport(uri);
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_home, container, false);
        View btnPlay = view.findViewById(R.id.btnPlay);
        View discordBtn = view.findViewById(R.id.discordBtn);
        View webBtn = view.findViewById(R.id.webBtn);
        View youtubeBtn = view.findViewById(R.id.youtubeBtn);

        discordBtn.setOnClickListener(v -> openUrl(RemoteConfigManager.getString("discord")));
        webBtn.setOnClickListener(v -> openUrl(RemoteConfigManager.getString("website")));
        youtubeBtn.setOnClickListener(v -> openUrl(RemoteConfigManager.getString("youtube")));
        btnPlay.setOnClickListener(v -> runPreflight());
        return view;
    }

    private void runPreflight() {
        if (getContext() == null) return;

        File baseDir = GameStorage.getGameBaseDirectory(requireContext());
        GameDataImporter.Validation gta = GameDataImporter.validateInstalled(baseDir);
        if (!gta.valid) {
            showMissingDataDialog(gta.message);
            return;
        }

        List<String> errors = prepareSampBootstrap();
        if (!errors.isEmpty()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Thiếu dữ liệu SAMP")
                    .setMessage(String.join("\n", errors))
                    .setPositiveButton("Đóng", null)
                    .show();
            return;
        }

        boolean arm64 = Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a");
        if (!arm64) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Không hỗ trợ CPU hiện tại")
                    .setMessage("Client VN-RP hiện chỉ build arm64-v8a.\nABI máy: " + Arrays.toString(Build.SUPPORTED_ABIS))
                    .setPositiveButton("Đóng", null)
                    .show();
            return;
        }

        if (isLikelyEmulator()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Đang chạy máy giả lập")
                    .setMessage("Dữ liệu GTA đã đủ, nhưng source 2.11.311 hook trực tiếp libGame.so nên LDPlayer vẫn có thể crash.\n\nNên test thêm trên điện thoại Android ARM64 thật.")
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Vẫn thử Play", (d, w) -> startGame())
                    .show();
            return;
        }

        startGame();
    }

    private void showMissingDataDialog(String reason) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Chưa có dữ liệu GTA SA")
                .setMessage(reason + "\n\nHãy chọn thư mục GTA San Andreas Android mà bạn sở hữu. VN-RP sẽ sao chép dữ liệu cần thiết vào thư mục riêng của client; APK không tải hoặc kèm game GTA.")
                .setNegativeButton("Để sau", null)
                .setPositiveButton("Chọn thư mục GTA SA", (dialog, which) -> gtaFolderPicker.launch(null))
                .show();
    }

    private void beginImport(Uri uri) {
        if (getContext() == null) return;

        importDialog = new ProgressDialog(requireContext());
        importDialog.setTitle("Đang cài dữ liệu GTA SA");
        importDialog.setMessage("Đang kiểm tra...");
        importDialog.setIndeterminate(true);
        importDialog.setCancelable(false);
        importDialog.show();

        File destination = GameStorage.getGameBaseDirectory(requireContext());
        GameDataImporter.importFromTree(requireContext(), uri, destination, new GameDataImporter.Callback() {
            @Override
            public void onStatus(String message) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (importDialog != null && importDialog.isShowing()) importDialog.setMessage(message);
                });
            }

            @Override
            public void onComplete(GameDataImporter.Validation validation) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    dismissImportDialog();
                    prepareSampBootstrap();
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Cài dữ liệu thành công")
                            .setMessage(validation.message + "\n\nBạn có thể bấm Play để thử vào game.")
                            .setPositiveButton("OK", null)
                            .show();
                });
            }

            @Override
            public void onError(String message, Throwable error) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    dismissImportDialog();
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Không thể cài dữ liệu")
                            .setMessage(message)
                            .setPositiveButton("Chọn lại", (d, w) -> gtaFolderPicker.launch(null))
                            .setNegativeButton("Đóng", null)
                            .show();
                });
            }
        });
    }

    private List<String> prepareSampBootstrap() {
        List<String> errors = new ArrayList<>();
        File sampDir = GameStorage.getSampDirectory(requireContext());

        for (String[] mapping : SAMP_BOOTSTRAP_FILES) {
            File destination = new File(sampDir, mapping[1]);
            try {
                if (!destination.exists() || destination.length() == 0) {
                    copyAsset(mapping[0], destination);
                }
                if (!destination.exists() || destination.length() == 0) {
                    errors.add(mapping[1]);
                }
            } catch (Throwable throwable) {
                errors.add(mapping[1] + " (" + throwable.getClass().getSimpleName() + ")");
            }
        }
        return errors;
    }

    private void startGame() {
        rotateNativeLog();
        Intent intent = new Intent(requireActivity(), SAMP.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        requireActivity().overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void rotateNativeLog() {
        try {
            File base = GameStorage.getGameBaseDirectory(requireContext());
            File current = new File(base, "samp_log.txt");
            File previous = new File(base, "samp_log.prev.txt");
            if (previous.exists()) previous.delete();
            if (current.exists()) current.renameTo(previous);
        } catch (Throwable ignored) { }
    }

    private void copyAsset(String assetPath, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File temp = new File(destination.getAbsolutePath() + ".tmp");
        try (InputStream input = requireContext().getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(temp, false)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
        }
        if (destination.exists() && !destination.delete()) throw new IllegalStateException("replace failed");
        if (!temp.renameTo(destination)) throw new IllegalStateException("move failed");
    }

    private boolean isLikelyEmulator() {
        String fingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT.toLowerCase();
        String model = Build.MODEL == null ? "" : Build.MODEL.toLowerCase();
        String hardware = Build.HARDWARE == null ? "" : Build.HARDWARE.toLowerCase();
        return fingerprint.contains("generic") || fingerprint.contains("emulator") ||
                model.contains("emulator") || model.contains("ldplayer") ||
                hardware.contains("goldfish") || hardware.contains("ranchu") || hardware.contains("vbox");
    }

    private void dismissImportDialog() {
        if (importDialog != null) {
            try { importDialog.dismiss(); } catch (Throwable ignored) { }
            importDialog = null;
        }
    }

    private void openUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override
    public void onDestroyView() {
        dismissImportDialog();
        super.onDestroyView();
    }
}
