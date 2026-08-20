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
        // Offline skins are local data and must not be gated behind Microsoft authentication.
        boolean offlineUnlocked = true;
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

    private void setupInstanceSettings() {
        binding.textFolder.setText(getString(R.string.launcher_folder_value, PathManager.DIR_MINECRAFT_HOME));

        boolean showSharedInstalls = LauncherPreferences.isShowSharedInstalls(this);
        binding.switchShowSharedInstalls.setChecked(showSharedInstalls);
        updateSharedInstallsSwitchText(showSharedInstalls);
        binding.switchShowSharedInstalls.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setShowSharedInstalls(this, isChecked);
            updateSharedInstallsSwitchText(isChecked);
        });

        // Checked = remove the inherited/base Minecraft version after the loader profile is flattened.
        // Unchecked = keep/install the inherited/base version.
        boolean removeInheritedVanilla = LauncherPreferences.isRemoveInheritedVanillaAfterLoaderInstall(this);
        binding.switchRemoveInheritedVanilla.setChecked(removeInheritedVanilla);
        updateRemoveInheritedVanillaSwitchText(removeInheritedVanilla);
        binding.switchRemoveInheritedVanilla.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setRemoveInheritedVanillaAfterLoaderInstall(this, isChecked);
            updateRemoveInheritedVanillaSwitchText(isChecked);
        });
    }

    private void setupRendererSettings() {
        binding.spinnerRenderer.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                if (!rendererSpinnerReady || position < 0 || position >= availableRenderers.size()) return;
                RendererInterface renderer = availableRenderers.get(position);
                LauncherPreferences.setSelectedRendererIdentifier(LauncherSettingsActivity.this, renderer.getUniqueIdentifier());
                Renderers.setCurrentRenderer(LauncherSettingsActivity.this, renderer.getUniqueIdentifier(), true);
                updateRendererDescription(renderer);
                updateRendererPluginButtons(renderer);
                updateVulkanDriverSettings(renderer);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        binding.spinnerVulkanDriver.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                if (!driverSpinnerReady || position < 0 || position >= availableDrivers.size()) return;
                Driver driver = availableDrivers.get(position);
                LauncherPreferences.setSelectedVulkanDriverName(LauncherSettingsActivity.this, driver.getName());
                updateVulkanDriverDescription(driver);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        binding.buttonImportRendererPlugin.setOnClickListener(view -> openSelectedRendererPluginSettings());
        binding.buttonGrantRendererStorageAccess.setOnClickListener(view -> openJavaLauncherStorageAccessSettings());
        binding.buttonClearRendererPluginCache.setOnClickListener(view -> clearRendererPluginCache());
        binding.buttonRefreshRenderers.setOnClickListener(view -> {
            Renderers.reload(this);
            DriverPluginManager.reload(this);
            refreshRendererList();
        });

        boolean useSystemVulkanDriver = LauncherPreferences.isUseSystemVulkanDriver(this);
        binding.switchUseSystemVulkanDriver.setChecked(useSystemVulkanDriver);
        updateSystemVulkanDriverSwitchText(useSystemVulkanDriver);
        binding.switchUseSystemVulkanDriver.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setUseSystemVulkanDriver(this, isChecked);
            updateSystemVulkanDriverSwitchText(isChecked);
            updateVulkanDriverSettings(getSelectedRendererFromSpinner());
        });

        boolean useOpenGl26Plus = LauncherPreferences.isUseOpenGlForMinecraft26Plus(this);
        binding.switchUseOpenGlFor26Plus.setChecked(useOpenGl26Plus);
        updateOpenGl26PlusSwitchText(useOpenGl26Plus);
        binding.switchUseOpenGlFor26Plus.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setUseOpenGlForMinecraft26Plus(this, isChecked);
            updateOpenGl26PlusSwitchText(isChecked);
        });

        Renderers.reload(this);
        refreshRendererList();
    }

    private void refreshRendererList() {
        rendererSpinnerReady = false;
        availableRenderers.clear();
        availableRenderers.addAll(Renderers.getCompatibleRenderers(this));

        ArrayList<String> names = new ArrayList<>();
        for (RendererInterface renderer : availableRenderers) {
            names.add(renderer.getRendererName() + (renderer.isExternalPlugin() ? "  •  Plugin" : ""));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerRenderer.setAdapter(adapter);

        if (availableRenderers.isEmpty()) {
            binding.textRendererDescription.setText(R.string.renderer_none_found);
            updateRendererPluginButtons(null);
            updateMobileGluesConfigSummary(null);
            updateVulkanDriverSettings(null);
            return;
        }

        int selectedIndex = Renderers.indexOfRenderer(availableRenderers, LauncherPreferences.getSelectedRendererIdentifier(this));
        binding.spinnerRenderer.setSelection(selectedIndex, false);
        updateRendererDescription(availableRenderers.get(selectedIndex));
        updateRendererPluginButtons(availableRenderers.get(selectedIndex));
        updateVulkanDriverSettings(availableRenderers.get(selectedIndex));
        rendererSpinnerReady = true;
    }

    private void updateVulkanDriverSettings(@Nullable RendererInterface renderer) {
        boolean show = DriverPluginManager.isVulkanZinkRenderer(renderer)
                && !LauncherPreferences.isUseSystemVulkanDriver(this);
        binding.layoutVulkanDriverSettings.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            driverSpinnerReady = false;
            availableDrivers.clear();
            binding.spinnerVulkanDriver.setAdapter(null);
            binding.textVulkanDriverDescription.setText("");
            return;
        }

        refreshVulkanDriverList();
    }

    private void refreshVulkanDriverList() {
        driverSpinnerReady = false;
        availableDrivers.clear();
        availableDrivers.addAll(DriverPluginManager.getDrivers(this));

        ArrayList<String> names = new ArrayList<>();
        for (Driver driver : availableDrivers) {
            names.add(driver.getName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerVulkanDriver.setAdapter(adapter);

        if (availableDrivers.isEmpty()) {
            binding.textVulkanDriverDescription.setText("");
            return;
        }

        int selectedIndex = DriverPluginManager.indexOfDriver(this, LauncherPreferences.getSelectedVulkanDriverName(this));
        binding.spinnerVulkanDriver.setSelection(selectedIndex, false);
        updateVulkanDriverDescription(availableDrivers.get(selectedIndex));
        driverSpinnerReady = true;
    }

    private void updateVulkanDriverDescription(@NonNull Driver driver) {
        String description = driver.getDescription();
        if (description == null || description.trim().isEmpty()) {
            description = "Uses the selected Vulkan driver for Vulkan/Zink rendering.";
        }

        binding.textVulkanDriverDescription.setText(getString(
                R.string.vulkan_driver_description_value,
                driver.getName(),
                description
        ));
    }

    private void updateRendererDescription(@NonNull RendererInterface renderer) {
        binding.textRendererDescription.setText(buildFriendlyRendererDescription(renderer));
        updateMobileGluesConfigSummary(renderer);
    }

    @NonNull
    private String buildFriendlyRendererDescription(@NonNull RendererInterface renderer) {
        String name = renderer.getRendererName();
        String lookup = (
                renderer.getRendererName() + " "
                        + renderer.getRendererId() + " "
                        + renderer.getUniqueIdentifier() + " "
                        + renderer.getRendererLibrary()
        ).toLowerCase();

        if (lookup.contains("mobileglues") || lookup.contains("mobile glues")) {
            return name + "\nRecommended for most Android devices. Good balance of compatibility and performance for modern Minecraft versions.";
        }

        if (lookup.contains("vulkan") || lookup.contains("zink")) {
            return name + "\nUses Vulkan/Zink rendering. Best for devices with strong Vulkan support, and useful for newer Minecraft versions or Vulkan-focused testing.";
        }

        if (lookup.contains("gl4es") || lookup.contains("opengles")) {
            return name + "\nClassic OpenGL ES compatibility renderer. Useful for older Minecraft versions or devices that do not work well with Vulkan.";
        }

        if (lookup.contains("virgl")) {
            return name + "\nCompatibility renderer for specific devices and setups. Try this if the recommended renderer does not work correctly.";
        }

        String description = renderer.getRendererDescription();
        if (description != null && !description.trim().isEmpty()) {
            return name + "\n" + description.trim();
        }

        return name + "\nRuns Minecraft using this renderer.";
    }

    private void updateMobileGluesConfigSummary(@Nullable RendererInterface renderer) {
        boolean mobileGlues = MobileGluesConfigHelper.isMobileGluesRenderer(renderer);

        if (!mobileGlues) {
            binding.textRendererPluginConfig.setText("");
            binding.textRendererPluginConfig.setVisibility(View.GONE);
            binding.buttonGrantRendererStorageAccess.setVisibility(View.GONE);
            return;
        }

        binding.textRendererPluginConfig.setText(MobileGluesConfigHelper.buildSettingsSummary(this, renderer));
        binding.textRendererPluginConfig.setVisibility(View.VISIBLE);

        boolean hasAccess = MobileGluesConfigHelper.hasStorageAccess(this);
        binding.buttonGrantRendererStorageAccess.setVisibility(View.VISIBLE);
        binding.buttonGrantRendererStorageAccess.setEnabled(true);
        binding.buttonGrantRendererStorageAccess.setText(hasAccess
                ? "Choose MobileGlues folder again"
                : "Choose MobileGlues folder");
    }

    private void updateRendererPluginButtons(@Nullable RendererInterface renderer) {
        boolean externalPlugin = renderer != null && renderer.isExternalPlugin();
        binding.buttonImportRendererPlugin.setEnabled(externalPlugin);
        binding.buttonClearRendererPluginCache.setEnabled(RendererPluginManager.hasImportedOrCachedRendererPlugins(this));
    }

    private void openSelectedRendererPluginSettings() {
        RendererInterface renderer = getSelectedRendererFromSpinner();
        if (renderer == null || !renderer.isExternalPlugin()) {
            return;
        }

        RendererPluginManager.openPluginApp(this, renderer);
    }

    private void openJavaLauncherStorageAccessSettings() {
        if (mobileGluesFolderPickerLauncher == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        mobileGluesFolderPickerLauncher.launch(intent);
    }

    @Nullable
    private RendererInterface getSelectedRendererFromSpinner() {
        int position = binding.spinnerRenderer.getSelectedItemPosition();
        if (position < 0 || position >= availableRenderers.size()) return null;
        return availableRenderers.get(position);
    }

    private void clearRendererPluginCache() {
        RendererPluginManager.clearImportedAndCachedRendererPlugins(this);
        Renderers.reload(this);
        refreshRendererList();
    }

    private void setupRenderSurfaceSettings() {
        boolean useNativeSurfaceView = LauncherPreferences.isUseNativeSurfaceView(this);
        binding.switchUseNativeSurface.setChecked(useNativeSurfaceView);
        updateRenderSurfaceSwitchText(useNativeSurfaceView);
        binding.switchUseNativeSurface.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setUseNativeSurfaceView(this, isChecked);
            updateRenderSurfaceSwitchText(isChecked);
        });

        setupGameDisplaySettings();
    }

    private void setupGameDisplaySettings() {
        int currentScale = LauncherPreferences.getGameResolutionScalePercent(this);
        binding.sliderGameResolutionScale.setMax("(continue from original file)");
