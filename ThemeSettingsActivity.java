package com.mhb.mbrowser.Activity;

import android.view.LayoutInflater;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.gyf.immersionbar.ImmersionBar;
import com.mhb.mbrowser.R;
import com.mhb.mbrowser.base.BaseActivity;
import com.mhb.mbrowser.databinding.ActivityThemeSettingsBinding;
import com.mhb.mbrowser.feature.theme.data.LauncherAliasRegistry;
import com.mhb.mbrowser.feature.theme.data.ThemePreferenceStore;

/**
 * 主题色与桌面启动图标/名称（多入口 Launcher Activity）设置。
 */
public class ThemeSettingsActivity extends BaseActivity<ActivityThemeSettingsBinding> {

    private ThemePreferenceStore themePreferenceStore;
    private boolean suppressGroupCallback;

    @NonNull
    @Override
    protected ActivityThemeSettingsBinding createViewBinding(@NonNull LayoutInflater inflater) {
        return ActivityThemeSettingsBinding.inflate(inflater);
    }

    @Override
    protected void initView() {
        themePreferenceStore = new ThemePreferenceStore(this);
        ImmersionBar.with(this)
                .fitsSystemWindows(true)
                .statusBarColor(R.color.black)
                .navigationBarColor(R.color.black)
                .init();

        binding.textToolbarTitle.setText(R.string.theme_settings_title);
        binding.textBack.setOnClickListener(v -> finish());

        syncAccentSelection();
        syncLauncherSelection();

        binding.groupAccent.setOnCheckedChangeListener(this::onAccentChecked);
        binding.groupLauncher.setOnCheckedChangeListener(this::onLauncherChecked);
    }

    private void syncAccentSelection() {
        suppressGroupCallback = true;
        int accent = themePreferenceStore.getAccentId();
        int checkId;
        switch (accent) {
            case ThemePreferenceStore.ACCENT_VIOLET:
                checkId = R.id.radio_accent_violet;
                break;
            case ThemePreferenceStore.ACCENT_TEAL:
                checkId = R.id.radio_accent_teal;
                break;
            case ThemePreferenceStore.ACCENT_CORAL:
                checkId = R.id.radio_accent_coral;
                break;
            case ThemePreferenceStore.ACCENT_SKY:
                checkId = R.id.radio_accent_sky;
                break;
            case ThemePreferenceStore.ACCENT_DISCORD:
            default:
                checkId = R.id.radio_accent_discord;
                break;
        }
        binding.groupAccent.check(checkId);
        suppressGroupCallback = false;
    }

    private void syncLauncherSelection() {
        suppressGroupCallback = true;
        String alias = themePreferenceStore.getLauncherAliasClassName();
        int checkId;
        if (LauncherAliasRegistry.CLASS_BROWSER.equals(alias)) {
            checkId = R.id.radio_launcher_browser;
        } else if (LauncherAliasRegistry.CLASS_LITE.equals(alias)) {
            checkId = R.id.radio_launcher_lite;
        } else {
            checkId = R.id.radio_launcher_default;
        }
        binding.groupLauncher.check(checkId);
        suppressGroupCallback = false;
    }

    private void onAccentChecked(@Nullable RadioGroup group, int checkedId) {
        if (suppressGroupCallback || checkedId == -1) {
            return;
        }
        int accentId;
        if (checkedId == R.id.radio_accent_violet) {
            accentId = ThemePreferenceStore.ACCENT_VIOLET;
        } else if (checkedId == R.id.radio_accent_teal) {
            accentId = ThemePreferenceStore.ACCENT_TEAL;
        } else if (checkedId == R.id.radio_accent_coral) {
            accentId = ThemePreferenceStore.ACCENT_CORAL;
        } else if (checkedId == R.id.radio_accent_sky) {
            accentId = ThemePreferenceStore.ACCENT_SKY;
        } else {
            accentId = ThemePreferenceStore.ACCENT_DISCORD;
        }
        if (accentId == themePreferenceStore.getAccentId()) {
            return;
        }
        themePreferenceStore.setAccentId(accentId);
        recreate();
    }

    private void onLauncherChecked(@Nullable RadioGroup group, int checkedId) {
        if (suppressGroupCallback || checkedId == -1) {
            return;
        }
        String aliasClass;
        if (checkedId == R.id.radio_launcher_browser) {
            aliasClass = LauncherAliasRegistry.CLASS_BROWSER;
        } else if (checkedId == R.id.radio_launcher_lite) {
            aliasClass = LauncherAliasRegistry.CLASS_LITE;
        } else {
            aliasClass = LauncherAliasRegistry.CLASS_DEFAULT;
        }
        if (aliasClass.equals(themePreferenceStore.getLauncherAliasClassName())) {
            return;
        }
        themePreferenceStore.setLauncherAliasClassName(aliasClass);
        LauncherAliasRegistry.applyEnabledAlias(this, aliasClass);
        showToast(getString(R.string.theme_settings_launcher_applied_toast));
    }

    @Override
    protected void onDestroy() {
        themePreferenceStore = null;
        super.onDestroy();
    }
}
