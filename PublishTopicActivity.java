package com.mhb.mbrowser.Activity;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;

import com.gyf.immersionbar.ImmersionBar;
import com.mhb.mbrowser.Adapter.PublishImageSlotsAdapter;
import com.mhb.mbrowser.R;
import com.mhb.mbrowser.base.BaseActivity;
import com.mhb.mbrowser.databinding.ActivityPublishTopicBinding;
import com.mhb.mbrowser.feature.auth.data.TokenLocalStore;
import com.mhb.mbrowser.feature.profile.data.FastUserProfileRepository;
import com.mhb.mbrowser.feature.square.data.FastTopicPublishRepository;
import com.mhb.mbrowser.feature.square.data.TopicNode;
import com.mhb.mbrowser.feature.square.presentation.PublishTopicContract;
import com.mhb.mbrowser.feature.square.presentation.PublishTopicPresenter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 发帖页：横向选择话题节点、正文与关联网址、配图（最多 9 张）；无独立标题字段，接口标题由正文首行推导。
 */
public class PublishTopicActivity extends BaseActivity<ActivityPublishTopicBinding>
        implements PublishTopicContract.View {

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<PickVisualMediaRequest> pickImagesLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.PickMultipleVisualMedia(9),
                    this::onVisualMediaPicked
            );

    @Nullable
    private PublishTopicContract.Presenter presenter;
    @NonNull
    private final List<TopicNode> nodes = new ArrayList<>();
    @NonNull
    private final List<PublishImageSlotsAdapter.Slot> imageSlots = new ArrayList<>();
    @Nullable
    private PublishImageSlotsAdapter imageAdapter;
    private int selectedNodeIndex;

    @NonNull
    @Override
    protected ActivityPublishTopicBinding createViewBinding(@NonNull LayoutInflater inflater) {
        return ActivityPublishTopicBinding.inflate(inflater);
    }

    @Override
    protected void initView() {
        ImmersionBar.with(this)
                .fitsSystemWindows(true)
                .statusBarColor(R.color.black)
                .navigationBarColor(R.color.black)
                .init();

        TokenLocalStore tokenStore = new TokenLocalStore(this);
        if (!tokenStore.hasLoginToken()) {
            showToast(getString(R.string.publish_need_login));
            finish();
            return;
        }

        imageAdapter = new PublishImageSlotsAdapter();
        imageAdapter.setListener(new PublishImageSlotsAdapter.Listener() {
            @Override
            public void onRemoveClick(int slotIndex) {
                if (slotIndex < 0 || slotIndex >= imageSlots.size()) {
                    return;
                }
                imageSlots.remove(slotIndex);
                if (imageAdapter != null) {
                    imageAdapter.submit(new ArrayList<>(imageSlots));
                }
                updateImageUiState();
            }

            @Override
            public void onAddClick() {
                launchImagePicker();
            }
        });
        binding.recyclerImages.setLayoutManager(new GridLayoutManager(this, 3));
        binding.recyclerImages.setAdapter(imageAdapter);

        presenter = new PublishTopicPresenter(
                this,
                new FastTopicPublishRepository(tokenStore, "publish_topic_http"),
                new FastUserProfileRepository(tokenStore, "publish_topic_upload")
        );
        presenter.loadNodes();

        binding.textBack.setOnClickListener(v -> finish());
        binding.btnSubmit.setOnClickListener(v -> submitTopic());

        updateImageUiState();
    }

    private void launchImagePicker() {
        int room = 9 - imageSlots.size();
        if (room <= 0) {
            showToast(getString(R.string.publish_max_images));
            return;
        }
        PickVisualMediaRequest request = new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build();
        pickImagesLauncher.launch(request);
    }

    private void onVisualMediaPicked(@Nullable List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        int room = 9 - imageSlots.size();
        if (room <= 0) {
            return;
        }
        int take = Math.min(room, uris.size());
        ioExecutor.execute(() -> {
            for (int i = 0; i < take; i++) {
                Uri uri = uris.get(i);
                if (uri == null) {
                    continue;
                }
                File file = copyPickedImageToCache(uri);
                if (file == null) {
                    runOnUiThread(() -> showToast(getString(R.string.publish_pick_image_failed)));
                    continue;
                }
                final File toUpload = file;
                runOnUiThread(() -> addSlotAndStartUpload(toUpload));
            }
        });
    }

    private void addSlotAndStartUpload(@NonNull File file) {
        if (imageSlots.size() >= 9) {
            return;
        }
        imageSlots.add(PublishImageSlotsAdapter.Slot.uploading(file));
        if (imageAdapter != null) {
            imageAdapter.submit(new ArrayList<>(imageSlots));
        }
        updateImageUiState();
        startUploadForFile(file);
    }

    private void startUploadForFile(@NonNull File file) {
        if (presenter == null) {
            return;
        }
        presenter.uploadImage(file, new PublishTopicContract.OnImageUploadListener() {
            @Override
            public void onUploaded(@NonNull String relativePath) {
                int idx = findUploadingSlotIndex(file);
                if (idx < 0) {
                    return;
                }
                imageSlots.set(idx, PublishImageSlotsAdapter.Slot.ready(relativePath));
                if (imageAdapter != null) {
                    imageAdapter.submit(new ArrayList<>(imageSlots));
                }
                updateImageUiState();
            }

            @Override
            public void onFailed(@NonNull String message) {
                int idx = findUploadingSlotIndex(file);
                if (idx >= 0) {
                    imageSlots.remove(idx);
                    if (imageAdapter != null) {
                        imageAdapter.submit(new ArrayList<>(imageSlots));
                    }
                    updateImageUiState();
                }
                showToast(message);
            }
        });
    }

    private int findUploadingSlotIndex(@NonNull File file) {
        for (int i = 0; i < imageSlots.size(); i++) {
            PublishImageSlotsAdapter.Slot s = imageSlots.get(i);
            if (s.state == PublishImageSlotsAdapter.SlotState.UPLOADING
                    && s.localFile != null
                    && file.equals(s.localFile)) {
                return i;
            }
        }
        return -1;
    }

    private void submitTopic() {
        if (presenter == null || nodes.isEmpty()) {
            showToast(getString(R.string.publish_nodes_not_ready));
            return;
        }
        if (selectedNodeIndex < 0 || selectedNodeIndex >= nodes.size()) {
            showToast(getString(R.string.publish_select_tag));
            return;
        }
        int nodeId = nodes.get(selectedNodeIndex).getId();
        EditText editContent = binding.editContent;
        List<String> urls = new ArrayList<>();
        for (PublishImageSlotsAdapter.Slot s : imageSlots) {
            if (s.state == PublishImageSlotsAdapter.SlotState.UPLOADING) {
                showToast(getString(R.string.publish_wait_upload));
                return;
            }
            if (s.state == PublishImageSlotsAdapter.SlotState.READY && s.relativeUrl != null) {
                urls.add(s.relativeUrl);
            }
        }
        presenter.publish(
                nodeId,
                deriveTitleFromContent(editContent.getText().toString()),
                editContent.getText().toString(),
                binding.editWebsiteUrl.getText().toString(),
                urls
        );
    }

    @NonNull
    private static String deriveTitleFromContent(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return "";
        }
        int nl = t.indexOf('\n');
        String first = nl >= 0 ? t.substring(0, nl).trim() : t;
        if (first.length() > 120) {
            return first.substring(0, 120);
        }
        return first;
    }

    private void updateImageUiState() {
        int n = imageSlots.size();
        binding.textImageCount.setText(getString(R.string.publish_image_count_fmt, n, 9));
        boolean anyUploading = false;
        for (PublishImageSlotsAdapter.Slot s : imageSlots) {
            if (s.state == PublishImageSlotsAdapter.SlotState.UPLOADING) {
                anyUploading = true;
                break;
            }
        }
        binding.btnSubmit.setEnabled(!anyUploading);
    }

    @Nullable
    private File copyPickedImageToCache(@NonNull Uri uri) {
        String ext = ".jpg";
        String mime = getContentResolver().getType(uri);
        if ("image/png".equals(mime)) {
            ext = ".png";
        } else if ("image/webp".equals(mime)) {
            ext = ".webp";
        }
        File out = new File(getCacheDir(), "publish_pick_" + System.currentTimeMillis() + ext);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream outStream = new FileOutputStream(out)) {
            if (in == null) {
                return null;
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                outStream.write(buf, 0, n);
            }
            outStream.flush();
            return out;
        } catch (IOException e) {
            if (out.exists() && !out.delete()) {
                // ignore
            }
            return null;
        }
    }

    @Override
    public void showNodes(@NonNull List<TopicNode> list) {
        nodes.clear();
        nodes.addAll(list);
        bindNodeTags();
    }

    private void bindNodeTags() {
        binding.linearTags.removeAllViews();
        selectedNodeIndex = 0;
        if (nodes.isEmpty()) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int padH = (int) (16 * density + 0.5f);
        int padV = (int) (6 * density + 0.5f);
        int gap = (int) (8 * density + 0.5f);
        for (int i = 0; i < nodes.size(); i++) {
            TextView tv = new TextView(this);
            tv.setText(nodes.get(i).getName());
            tv.setPadding(padH, padV, padH, padV);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(gap);
            tv.setLayoutParams(lp);
            final int index = i;
            tv.setOnClickListener(v -> selectNodeTag(index));
            binding.linearTags.addView(tv);
        }
        selectNodeTag(0);
    }

    private void selectNodeTag(int index) {
        if (index < 0 || index >= binding.linearTags.getChildCount()) {
            return;
        }
        selectedNodeIndex = index;
        for (int i = 0; i < binding.linearTags.getChildCount(); i++) {
            TextView tv = (TextView) binding.linearTags.getChildAt(i);
            boolean active = i == index;
            tv.setBackgroundResource(active ? R.drawable.bg_publish_tag_active : R.drawable.bg_publish_tag_inactive);
            tv.setTextColor(active ? Color.WHITE : Color.parseColor("#888888"));
            tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    @Override
    public void showMessage(@NonNull String message) {
        showToast(message);
    }

    @Override
    public void setPublishInProgress(boolean inProgress) {
        binding.btnSubmit.setEnabled(!inProgress);
        binding.progressFullscreen.setVisibility(inProgress ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    @Override
    public void onPublishSucceeded() {
        showToast(getString(R.string.publish_success));
        setResult(Activity.RESULT_OK);
        finish();
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        if (presenter != null) {
            presenter.onDestroy();
            presenter = null;
        }
        binding.recyclerImages.setAdapter(null);
        imageAdapter = null;
        super.onDestroy();
    }
}
