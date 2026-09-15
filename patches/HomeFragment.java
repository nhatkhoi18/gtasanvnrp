package com.kurdish.roleplay.launcher;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kurdish.roleplay.R;
import com.kurdish.roleplay.game.SAMP;
import com.kurdish.roleplay.launcher.util.GameStorage;
import com.kurdish.roleplay.launcher.util.RemoteConfigManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HomeFragment extends Fragment {

    private static final String[][] SAMP_BOOTSTRAP_FILES = new String[][] {
            {"data/script/mainv1.scm", "main.scm"},
            {"data/script/scriptv1.img", "script.img"},
            {"data/peds.ide", "peds.ide"},
            {"data/vehicles.ide", "vehicles.ide"},
            {"data/gta.dat", "gta.dat"},
            {"data/handling.cfg", "handling.cfg"},
            {"data/weapon.dat", "weapon.dat"}
    };

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
        List<String> errors = new ArrayList<>();
        List<String> copied = new ArrayList<>();
        File sampDir = GameStorage.getSampDirectory(requireContext());

        for (String[] mapping : SAMP_BOOTSTRAP_FILES) {
            File destination = new File(sampDir, mapping[1]);
            try {
                if (!destination.exists() || destination.length() == 0) {
                    copyAsset(mapping[0], destination);
                    copied.add(mapping[1]);
                }
                if (!destination.exists() || destination.length() == 0) {
                    errors.add(mapping[1]);
                }
            } catch (Throwable throwable) {
                errors.add(mapping[1] + " (" + throwable.getClass().getSimpleName() + ")");
            }
        }

        boolean arm64 = Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a");
        boolean emulator = isLikelyEmulator();
        writeBootReport(sampDir, copied, errors, arm64, emulator);

        if (!arm64) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Không hỗ trợ CPU hiện tại")
                    .setMessage("Client này chỉ có native arm64-v8a.\n\n" +
                            "Thiết bị báo ABI: " + Arrays.toString(Build.SUPPORTED_ABIS) + "\n\n" +
                            "Nếu đang dùng LDPlayer/x86_64 thì launcher Java vẫn mở được, nhưng khi bấm Play native GTA/SAMP sẽ văng. Hãy test trên điện thoại Android ARM64 thật.")
                    .setPositiveButton("Đã hiểu", null)
                    .show();
            return;
        }

        if (!errors.isEmpty()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Thiếu dữ liệu khởi động")
                    .setMessage("Không thể chuẩn bị các file SAMP sau:\n\n" + String.join("\n", errors) +
                            "\n\nThư mục: " + sampDir.getAbsolutePath())
                    .setPositiveButton("Đóng", null)
                    .show();
            return;
        }

        if (emulator) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Phát hiện máy giả lập")
                    .setMessage("Model: " + Build.MODEL + "\nABI: " + Arrays.toString(Build.SUPPORTED_ABIS) +
                            "\n\nSource 2.11.311 dùng hook/patch ARM64 trực tiếp vào libGame.so. Máy giả lập có thể vẫn crash dù báo hỗ trợ ARM64. Nên test trên điện thoại ARM64 thật để xác nhận runtime.")
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Vẫn thử Play", (dialog, which) -> startGame())
                    .show();
            return;
        }

        startGame();
    }

    private void startGame() {
        android.util.Log.d("HomeFragment", "Preflight OK, starting SAMP");
        Intent intent = new Intent(requireActivity(), SAMP.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        requireActivity().overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void copyAsset(String assetPath, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File temp = new File(destination.getAbsolutePath() + ".tmp");
        try (InputStream input = requireContext().getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(temp, false)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
        }
        if (destination.exists() && !destination.delete()) {
            throw new IllegalStateException("Cannot replace " + destination.getName());
        }
        if (!temp.renameTo(destination)) {
            throw new IllegalStateException("Cannot move " + destination.getName());
        }
    }

    private boolean isLikelyEmulator() {
        String fingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT.toLowerCase();
        String model = Build.MODEL == null ? "" : Build.MODEL.toLowerCase();
        String product = Build.PRODUCT == null ? "" : Build.PRODUCT.toLowerCase();
        String hardware = Build.HARDWARE == null ? "" : Build.HARDWARE.toLowerCase();
        return fingerprint.contains("generic") || fingerprint.contains("emulator") ||
                model.contains("emulator") || model.contains("android sdk") || model.contains("ldplayer") ||
                product.contains("sdk") || product.contains("emulator") ||
                hardware.contains("goldfish") || hardware.contains("ranchu") || hardware.contains("vbox");
    }

    private void writeBootReport(File sampDir, List<String> copied, List<String> errors,
                                 boolean arm64, boolean emulator) {
        try {
            File report = new File(sampDir, "vnrp_boot.txt");
            String text = "VN-RP runtime preflight\n" +
                    "model=" + Build.MODEL + "\n" +
                    "manufacturer=" + Build.MANUFACTURER + "\n" +
                    "device=" + Build.DEVICE + "\n" +
                    "hardware=" + Build.HARDWARE + "\n" +
                    "abis=" + Arrays.toString(Build.SUPPORTED_ABIS) + "\n" +
                    "arm64=" + arm64 + "\n" +
                    "emulator=" + emulator + "\n" +
                    "base=" + GameStorage.getGameBasePath(requireContext()) + "\n" +
                    "copied=" + copied + "\n" +
                    "errors=" + errors + "\n";
            try (FileOutputStream output = new FileOutputStream(report, false)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private void openUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }
}
