/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, PojavLauncher, Zalith Launcher, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.javalauncher;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import ca.dnamobile.javalauncher.auth.MicrosoftAuthConfigPersonal;
import ca.dnamobile.javalauncher.auth.MicrosoftAuthManagerPersonal;
import ca.dnamobile.javalauncher.controls.ControlsActivity;
import ca.dnamobile.javalauncher.controls.ControlsPreferences;
import ca.dnamobile.javalauncher.data.AccountStore;
import ca.dnamobile.javalauncher.databinding.ActivityLauncherSettingsBinding;
import ca.dnamobile.javalauncher.feature.log.Logging;
import ca.dnamobile.javalauncher.input.GamepadMappingDialog;
import ca.dnamobile.javalauncher.input.GamepadMappingStore;
import ca.dnamobile.javalauncher.legal.LegalLinks;
import ca.dnamobile.javalauncher.logs.LauncherLogManager;
import ca.dnamobile.javalauncher.modcompat.AndroidMicrophonePermission;
import ca.dnamobile.javalauncher.notifications.LauncherNotificationPermissionHelper;
import ca.dnamobile.javalauncher.renderer.Driver;
import ca.dnamobile.javalauncher.renderer.DriverPluginManager;
import ca.dnamobile.javalauncher.renderer.MobileGluesConfigHelper;
import ca.dnamobile.javalauncher.renderer.RendererInterface;
import ca.dnamobile.javalauncher.renderer.RendererPluginManager;
import ca.dnamobile.javalauncher.renderer.Renderers;
import ca.dnamobile.javalauncher.settings.GameOverlayPreferences;
import ca.dnamobile.javalauncher.settings.LauncherPreferences;
import ca.dnamobile.javalauncher.settings.MemoryAllocationUtils;
import ca.dnamobile.javalauncher.skin.CustomSkinStore;
import ca.dnamobile.javalauncher.skin.MicrosoftSkinUploader;
import ca.dnamobile.javalauncher.skin.PlayerHeadLoader;
import ca.dnamobile.javalauncher.skin.SkinModelType;
import ca.dnamobile.javalauncher.update.LauncherUpdateDialogs;
import ca.dnamobile.javalauncher.update.LauncherUpdatePreferences;
import ca.dnamobile.javalauncher.utils.FullscreenUtils;
import ca.dnamobile.javalauncher.utils.path.PathManager;

public final class LauncherSettingsActivity extends AppCompatActivity {
    private static final String SETTINGS_DEFAULTS_PREFS = "launcher_settings_defaults";
    private static final String SETTINGS_DEFAULTS_APPLIED_KEY = "settings_defaults_applied_2026_04_instances";

    private ActivityLauncherSettingsBinding binding;
    private AccountStore accountStore;
    private MicrosoftAuthManagerPersonal authManager;
    private CustomSkinStore customSkinStore;
    private ActivityResultLauncher<Intent> customSkinPickerLauncher;
    private ActivityResultLauncher<Intent> microsoftSkinPickerLauncher;
    private ActivityResultLauncher<Intent> offlineSkinPickerLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private ActivityResultLauncher<String> microphonePermissionLauncher;
    private ActivityResultLauncher<Intent> mobileGluesFolderPickerLauncher;
    private Uri pendingOfflineSkinUri;
    private ImageView pendingOfflineSkinPreview;
    private TextView pendingOfflineSkinLabel;
    private AlertDialog offlineAccountsDialog;
    private final List<RendererInterface> availableRenderers = new ArrayList<>();
    private final List<Driver> availableDrivers = new ArrayList<>();
    private boolean rendererSpinnerReady;
    private boolean driverSpinnerReady;
    private TextView textHardwareMouseDpiScale;
    private SeekBar sliderHardwareMouseDpiScale;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PathManager.initContextConstants(this);
        binding = ActivityLauncherSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        FullscreenUtils.enableImmersive(this);

        binding.buttonSettingsBack.setOnClickListener(view -> finish());
        applySettingsDefaultsOnce();
        setupSettingsSectionTabs();
        registerSkinPickerLauncher();
        registerMicrosoftSkinPickerLauncher();
        registerOfflineSkinPickerLauncher();
        registerNotificationPermissionLauncher();
        registerMicrophonePermissionLauncher();
        registerMobileGluesFolderPickerLauncher();
        setupAccountUi();
        setupInstanceSettings();
        setupRendererSettings();
        setupRenderSurfaceSettings();
        setupControllerSettings();
        setupLauncherSettings();
        setupPrivacyPolicySettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        FullscreenUtils.enableImmersive(this);
        if (binding != null) {
            RendererInterface selectedRenderer = getSelectedRendererFromSpinner();
            updateMobileGluesConfigSummary(selectedRenderer);
            if (DriverPluginManager.isVulkanZinkRenderer(selectedRenderer)) {
                DriverPluginManager.reload(this);
                updateVulkanDriverSettings(selectedRenderer);
            }
            refreshControllerSettingsValues();
            updateInstallNotificationSettingsUi();
            updateSimpleVoiceChatPermissionUi();
            refreshAccountUiFromStore();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            FullscreenUtils.enableImmersive(this);
        }
    }

    @Override
    protected void onDestroy() {
        if (authManager != null && !isChangingConfigurations()) {
            authManager.dispose();
        }
        super.onDestroy();
    }

    private void refreshAccountUiFromStore() {
        if (accountStore == null || binding == null) return;

        try {
            AccountStore.Account account = accountStore.load();
            updateAccountStatus(account);
            updateSkinUi(account);
            updateChangeMicrosoftSkinButtonState(account);
        } catch (Throwable throwable) {
            Logging.e("LauncherSettings", "Unable to refresh account UI", throwable);
        }
    }

    private void applySettingsDefaultsOnce() {
        android.content.SharedPreferences preferences = getSharedPreferences(SETTINGS_DEFAULTS_PREFS, MODE_PRIVATE);
        if (preferences.getBoolean(SETTINGS_DEFAULTS_APPLIED_KEY, false)) return;

        // Defaults requested for the settings screen:
        // - Shared installs hidden/off by default.
        // - Keep inherited/base Minecraft versions on by default.
        LauncherPreferences.setShowSharedInstalls(this, false);
        LauncherPreferences.setRemoveInheritedVanillaAfterLoaderInstall(this, false);
        preferences.edit().putBoolean(SETTINGS_DEFAULTS_APPLIED_KEY, true).apply();
    }

    private void setupSettingsSectionTabs() {
        binding.settingsSectionTabs.removeAllTabs();
        addSettingsSectionTab(R.string.settings_account_title);
        addSettingsSectionTab(R.string.renderer_settings_title);
        addSettingsSectionTab(R.string.controller_settings_title);
        addSettingsSectionTab(R.string.settings_launcher_title);
        addSettingsSectionTab(R.string.settings_instance_title);
        addSettingsSectionTab("Privacy Policy");




        binding.settingsSectionTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                scrollToSettingsSection(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                scrollToSettingsSection(tab.getPosition());
            }
        });
    }

    private void addSettingsSectionTab(int titleResId) {
        TabLayout.Tab tab = binding.settingsSectionTabs.newTab();
        tab.setText(titleResId);
        binding.settingsSectionTabs.addTab(tab);
    }

    private void addSettingsSectionTab(@NonNull String title) {
        TabLayout.Tab tab = binding.settingsSectionTabs.newTab();
        tab.setText(title);
        binding.settingsSectionTabs.addTab(tab);
    }

    private void scrollToSettingsSection(int position) {
        View target;

        switch (position) {
            case 0:
                target = binding.cardAccountSettings;
                break;
            case 1:
                target = binding.cardRendererSettings;
                break;
            case 2:
                target = binding.cardControllerSettings;
                break;
            case 3:
                target = binding.cardLauncherSettings;
                break;
            case 4:
                target = binding.cardInstanceSettings;
                break;
            case 5:
                target = binding.cardPrivacyPolicySettings;
                break;
            default:
                return;
        }

        binding.settingsScrollView.post(() ->
                binding.settingsScrollView.smoothScrollTo(0, Math.max(0, target.getTop() - dp(8)))
        );
    }

    private void setupAccountUi() {
        try {
            accountStore = new AccountStore(this);
            customSkinStore = new CustomSkinStore(this);
            authManager = new MicrosoftAuthManagerPersonal(this, accountStore);
            authManager.setListener(new MicrosoftAuthManagerPersonal.Listener() {
                @Override
                public void onSignedIn(@NonNull AccountStore.Account account) {
                    updateAccountStatus(account);
                    updateSkinUi(account);
                    updateChangeMicrosoftSkinButtonState(account);
                    binding.buttonRefreshMicrosoftSkin.setEnabled(true);
                }

                @Override
                public void onError(@NonNull String message) {
                    binding.textAccountStatus.setText(message);
                    binding.buttonRefreshMicrosoftSkin.setEnabled(true);
                    Toast.makeText(LauncherSettingsActivity.this, message, Toast.LENGTH_LONG).show();
                }
            });

            AccountStore.Account account = accountStore.load();
            updateAccountStatus(account);
            updateSkinUi(account);
        } catch (Throwable throwable) {
            Logging.e("LauncherSettings", "Microsoft account UI initialization failed", throwable);
            binding.textAccountStatus.setText(R.string.status_signed_out);
            binding.buttonSignIn.setEnabled(false);
            binding.buttonSignOut.setEnabled(false);
            binding.buttonManageOfflineAccounts.setEnabled(false);
            binding.buttonUseMicrosoftAccount.setEnabled(false);
            binding.buttonRefreshMicrosoftSkin.setEnabled(false);
        }

        setupChangeMicrosoftSkinButton();

        binding.buttonSignIn.setOnClickListener(view -> {
            if (authManager == null) return;
            if (!MicrosoftAuthConfigPersonal.isConfigured()) {
                binding.textAccountStatus.setText(R.string.msg_configure_client_id);
                return;
            }
            authManager.signIn();
        });

        binding.buttonSignOut.setOnClickListener(view -> showSignOutConfirmationDialog());

        binding.buttonUseMicrosoftAccount.setOnClickListener(view -> useRememberedMicrosoftAccount());
        binding.buttonManageOfflineAccounts.setOnClickListener(view -> showOfflineAccountsDialog());
        binding.buttonRefreshMicrosoftSkin.setOnClickListener(view -> refreshMicrosoftAccountAndSkin(true));
        updateChangeMicrosoftSkinButtonState(accountStore != null ? accountStore.load() : null);
    }

    private void setupChangeMicrosoftSkinButton() {
        if (binding == null) return;

        binding.buttonChangeMicrosoftSkin.setOnClickListener(view -> showChangeMicrosoftSkinDialog());
    }

    private void showSignOutConfirmationDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sign_out_confirm_title)
                .setMessage(R.string.sign_out_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.button_sign_out, (dialog, which) -> performMicrosoftSignOut())
                .show();
    }

    private void performMicrosoftSignOut() {
        if (authManager == null || accountStore == null) return;

        authManager.signOut();

        AccountStore.Account account = accountStore.load();
        updateAccountStatus(account);
        updateSkinUi(account);

        if (binding.buttonRefreshMicrosoftSkin != null) {
            binding.buttonRefreshMicrosoftSkin.setEnabled(false);
        }
        updateChangeMicrosoftSkinButtonState(account);

        Toast.makeText(this, R.string.msg_sign_out_success, Toast.LENGTH_SHORT).show();
    }

    private void registerSkinPickerLauncher() {
        customSkinPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> { }
        );
    }

    private void registerMicrosoftSkinPickerLauncher() {
        microsoftSkinPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    Uri uri = result.getData().getData();
                    if (uri == null) return;
                    prepareMicrosoftSkinUpload(uri);
                }
        );
    }

    private void registerOfflineSkinPickerLauncher() {
        offlineSkinPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    Uri uri = result.getData().getData();
                    if (uri == null) return;
                    pendingOfflineSkinUri = uri;
                    if (pendingOfflineSkinPreview != null) {
                        updatePendingOfflineSkinPreview(uri);
                    }
                    if (pendingOfflineSkinLabel != null) {
                        pendingOfflineSkinLabel.setText(R.string.offline_account_skin_selected);
                    }
                }
        );
    }

    private void openCustomSkinPicker() {
        // Kept for old callers. Custom skins are now managed per offline profile.
        showOfflineAccountsDialog();
    }

    private void handleCustomSkinResult(@NonNull Uri uri) {
        // Kept for old callers. Custom skins are now managed per offline profile.
        pendingOfflineSkinUri = uri;
    }

    private void updateSkinUi(@Nullable AccountStore.Account account) {
        boolean offlineUnlocked = accountStore != null && accountStore.hasMicrosoftLoginCompletedOnce();
        boolean activeOfflineSkin = account != null && account.isOfflineAccount() && account.hasOfflineSkin();
        boolean microsoftSkin = account != null && account.isMicrosoftAccount() && !isNullOrBlank(account.skinUrl);
        boolean rememberedMicrosoft = accountStore != null && accountStore.hasStoredMicrosoftAccount();

        if (activeOfflineSkin) {
            binding.textSkinStatus.setText(getString(R.string.offline_account_skin_active, account.getBestDisplayName()));
        } else if (microsoftSkin) {
            binding.textSkinStatus.setText(R.string.custom_skin_status_microsoft);
        } else if (rememberedMicrosoft) {
            binding.textSkinStatus.setText(R.string.microsoft_skin_needs_refresh);
        } else if (!offlineUnlocked) {
            binding.textSkinStatus.setText(R.string.custom_skin_status_locked);
        } else {
            binding.textSkinStatus.setText(R.string.custom_skin_status_none);
        }

        PlayerHeadLoader.loadInto(this, binding.imagePlayerHead, account, null);
        updateChangeMicrosoftSkinButtonState(account);
    }

    // ... preserved original 0.8.3 implementation ...
}
