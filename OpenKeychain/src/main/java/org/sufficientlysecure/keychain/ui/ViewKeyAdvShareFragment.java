/*
 * Copyright (C) 2017 Schürmann & Breitmoser GbR
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.ui;


import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.security.NoSuchAlgorithmException;

import android.app.Activity;
import android.app.ActivityOptions;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Transformations;
import android.arch.lifecycle.ViewModelProviders;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v7.widget.CardView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.livedata.GenericLiveData;
import org.sufficientlysecure.keychain.model.SubKey.UnifiedKeyInfo;
import org.sufficientlysecure.keychain.pgp.CanonicalizedPublicKey;
import org.sufficientlysecure.keychain.pgp.SshPublicKey;
import org.sufficientlysecure.keychain.pgp.exception.PgpGeneralException;
import org.sufficientlysecure.keychain.pgp.exception.PgpKeyNotFoundException;
import org.sufficientlysecure.keychain.provider.KeyRepository;
import org.sufficientlysecure.keychain.provider.KeychainContract.KeyRings;
import org.sufficientlysecure.keychain.provider.TemporaryFileProvider;
import org.sufficientlysecure.keychain.ui.ViewKeyAdvActivity.ViewKeyAdvViewModel;
import org.sufficientlysecure.keychain.ui.util.FormattingUtils;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils;
import org.sufficientlysecure.keychain.ui.util.Notify;
import org.sufficientlysecure.keychain.ui.util.Notify.Style;
import org.sufficientlysecure.keychain.ui.util.QrCodeUtils;
import timber.log.Timber;

public class ViewKeyAdvShareFragment extends Fragment {
    private ImageView mQrCode;
    private CardView mQrCodeLayout;
    private TextView mFingerprintView;

    private Bitmap mQrCodeBitmapCache;
    private UnifiedKeyInfo unifiedKeyInfo;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup viewGroup, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.view_key_adv_share_fragment, viewGroup, false);

        mFingerprintView = view.findViewById(R.id.view_key_fingerprint);
        mQrCode = view.findViewById(R.id.view_key_qr_code);

        // We cache the QR code bitmap in its smallest possible size, then scale
        // it manually for the correct size whenever the layout of the ImageView
        // changes.  The fingerprint qr code loader which runs in the background
        // just calls requestLayout when it is finished, this way the loader and
        // background task are disconnected from any layouting the ImageView may
        // undergo. Please note how these six lines are perfectly right-aligned.
        mQrCode.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            // bitmap scaling is expensive, avoid doing it if we already have the correct size!
            int mCurrentWidth = 0, mCurrentHeight = 0;
            if (mQrCodeBitmapCache != null) {
                if (mCurrentWidth == mQrCode.getWidth() && mCurrentHeight == mQrCode.getHeight()) {
                    return;
                }
                mCurrentWidth = mQrCode.getWidth();
                mCurrentHeight = mQrCode.getHeight();
                // scale the image up to our actual size. we do this in code rather
                // than let the ImageView do this because we don't require filtering.
                Bitmap scaled = Bitmap.createScaledBitmap(mQrCodeBitmapCache,
                        mCurrentWidth, mCurrentHeight, false);
                mQrCode.setImageBitmap(scaled);
            }
        });
        mQrCodeLayout = view.findViewById(R.id.view_key_qr_code_layout);
        mQrCodeLayout.setOnClickListener(v -> showQrCodeDialog());

        View vFingerprintShareButton = view.findViewById(R.id.view_key_action_fingerprint_share);
        View vFingerprintClipboardButton = view.findViewById(R.id.view_key_action_fingerprint_clipboard);
        View vKeyShareButton = view.findViewById(R.id.view_key_action_key_share);
        View vKeyClipboardButton = view.findViewById(R.id.view_key_action_key_clipboard);
        ImageButton vKeySafeSlingerButton = view.findViewById(R.id.view_key_action_key_safeslinger);
        View vKeySshShareButton = view.findViewById(R.id.view_key_action_key_ssh_share);
        View vKeySshClipboardButton = view.findViewById(R.id.view_key_action_key_ssh_clipboard);
        View vKeyUploadButton = view.findViewById(R.id.view_key_action_upload);
        vKeySafeSlingerButton.setColorFilter(FormattingUtils.getColorFromAttr(requireContext(), R.attr.colorTertiaryText),
                PorterDuff.Mode.SRC_IN);

        vFingerprintShareButton.setOnClickListener(v -> shareFingerprint(false));
        vFingerprintClipboardButton.setOnClickListener(v -> shareFingerprint(true));
        vKeyShareButton.setOnClickListener(v -> shareKey(false, false));
        vKeyClipboardButton.setOnClickListener(v -> shareKey(true, false));

        vKeySafeSlingerButton.setOnClickListener(v -> startSafeSlinger());
        vKeySshShareButton.setOnClickListener(v -> shareKey(false, true));
        vKeySshClipboardButton.setOnClickListener(v -> shareKey(true, true));
        vKeyUploadButton.setOnClickListener(v -> uploadToKeyserver());

        return view;
    }

    private void startSafeSlinger() {
        Intent safeSlingerIntent = new Intent(getActivity(), SafeSlingerActivity.class);
        safeSlingerIntent.putExtra(SafeSlingerActivity.EXTRA_MASTER_KEY_ID, unifiedKeyInfo.master_key_id());
        startActivityForResult(safeSlingerIntent, 0);
    }

    private String getShareKeyContent(boolean asSshKey)
            throws PgpKeyNotFoundException, KeyRepository.NotFoundException, IOException, PgpGeneralException,
            NoSuchAlgorithmException {

        KeyRepository keyRepository = KeyRepository.create(requireContext());

        String content;
        if (asSshKey) {
            long authSubKeyId = keyRepository.getCachedPublicKeyRing(unifiedKeyInfo.master_key_id()).getAuthenticationId();
            CanonicalizedPublicKey publicKey = keyRepository.getCanonicalizedPublicKeyRing(unifiedKeyInfo.master_key_id())
                                                            .getPublicKey(authSubKeyId);
            SshPublicKey sshPublicKey = new SshPublicKey(publicKey);
            content = sshPublicKey.getEncodedKey();
        } else {
            content = keyRepository.getPublicKeyRingAsArmoredString(unifiedKeyInfo.master_key_id());
        }

        return content;
    }

    private void shareKey(boolean toClipboard, boolean asSshKey) {
        Activity activity = getActivity();
        if (activity == null || unifiedKeyInfo == null) {
            return;
        }
        if (asSshKey && !unifiedKeyInfo.has_auth_key()) {
            Notify.create(activity, R.string.authentication_subkey_not_found, Style.ERROR).show();
            return;
        }

        try {
            String content = getShareKeyContent(asSshKey);

            if (toClipboard) {
                ClipboardManager clipMan = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipMan == null) {
                    Notify.create(activity, R.string.error_clipboard_copy, Style.ERROR).show();
                    return;
                }

                ClipData clip = ClipData.newPlainText(Constants.CLIPBOARD_LABEL, content);
                clipMan.setPrimaryClip(clip);

                Notify.create(activity, R.string.key_copied_to_clipboard, Notify.Style.OK).show();
                return;
            }

            // let user choose application
            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType(Constants.MIME_TYPE_KEYS);

            // NOTE: Don't use Intent.EXTRA_TEXT to send the key
            // better send it via a Uri!
            // example: Bluetooth Share will convert text/plain sent via Intent.EXTRA_TEXT to HTML
            try {
                TemporaryFileProvider shareFileProv = new TemporaryFileProvider();

                String filename;
                if (unifiedKeyInfo.name() != null) {
                    filename = unifiedKeyInfo.name();
                } else {
                    filename = KeyFormattingUtils.convertFingerprintToHex(unifiedKeyInfo.fingerprint());
                }
                Uri contentUri = TemporaryFileProvider.createFile(activity, filename + Constants.FILE_EXTENSION_ASC);

                BufferedWriter contentWriter = new BufferedWriter(new OutputStreamWriter(
                        new ParcelFileDescriptor.AutoCloseOutputStream(
                                shareFileProv.openFile(contentUri, "w"))));
                contentWriter.write(content);
                contentWriter.close();

                sendIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            } catch (FileNotFoundException e) {
                Timber.e(e, "Error creating temporary key share file!");
                // no need for a snackbar because one sharing option doesn't work
                // Notify.create(getActivity(), R.string.error_temp_file, Notify.Style.ERROR).show();
            }

            String title = getString(R.string.title_share_key);
            Intent shareChooser = Intent.createChooser(sendIntent, title);

            startActivity(shareChooser);
        } catch (PgpGeneralException | IOException | NoSuchAlgorithmException e) {
            Timber.e(e, "error processing key!");
            Notify.create(activity, R.string.error_key_processing, Notify.Style.ERROR).show();
        } catch (PgpKeyNotFoundException | KeyRepository.NotFoundException e) {
            Timber.e(e, "key not found!");
            Notify.create(activity, R.string.error_key_not_found, Notify.Style.ERROR).show();
        }
    }

    private void shareFingerprint(boolean toClipboard) {
        Activity activity = getActivity();
        if (activity == null || unifiedKeyInfo == null) {
            return;
        }

        String content;
        String fingerprint = KeyFormattingUtils.convertFingerprintToHex(unifiedKeyInfo.fingerprint());
        if (!toClipboard) {
            content = Constants.FINGERPRINT_SCHEME + ":" + fingerprint;
        } else {
            content = fingerprint;
        }

        if (toClipboard) {
            ClipboardManager clipMan = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipMan == null) {
                Notify.create(activity, R.string.error_clipboard_copy, Style.ERROR).show();
                return;
            }

            ClipData clip = ClipData.newPlainText(Constants.CLIPBOARD_LABEL, content);
            clipMan.setPrimaryClip(clip);

            Notify.create(activity, R.string.fingerprint_copied_to_clipboard, Notify.Style.OK).show();
            return;
        }

        // let user choose application
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, content);
        sendIntent.setType("text/plain");

        String title = getString(R.string.title_share_fingerprint_with);
        Intent shareChooser = Intent.createChooser(sendIntent, title);

        startActivity(shareChooser);
    }

    private void showQrCodeDialog() {
        Intent qrCodeIntent = new Intent(getActivity(), QrCodeViewActivity.class);

        // create the transition animation - the images in the layouts
        // of both activities are defined with android:transitionName="qr_code"
        Bundle opts = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ActivityOptions options = ActivityOptions
                    .makeSceneTransitionAnimation(getActivity(), mQrCodeLayout, "qr_code");
            opts = options.toBundle();
        }

        qrCodeIntent.putExtra(QrCodeViewActivity.EXTRA_MASTER_KEY_ID, unifiedKeyInfo.master_key_id());
        ActivityCompat.startActivity(requireActivity(), qrCodeIntent, opts);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ViewKeyAdvViewModel viewModel = ViewModelProviders.of(requireActivity()).get(ViewKeyAdvViewModel.class);
        LiveData<UnifiedKeyInfo> unifiedKeyInfoLiveData = viewModel.getUnifiedKeyInfoLiveData(requireContext());
        unifiedKeyInfoLiveData.observe(this, this::onLoadUnifiedKeyInfo);

        LiveData<Bitmap> qrCodeLiveData = Transformations.switchMap(unifiedKeyInfoLiveData,
                (unifiedKeyInfo) -> new GenericLiveData<>(getContext(), null,
                        () -> {
                            String fingerprintHex = KeyFormattingUtils.convertFingerprintToHex(unifiedKeyInfo.fingerprint());
                            Uri uri = new Uri.Builder().scheme(Constants.FINGERPRINT_SCHEME).opaquePart(fingerprintHex).build();
                            // render with minimal size
                            return QrCodeUtils.getQRCodeBitmap(uri, 0);
                        }
                ));
        qrCodeLiveData.observe(this, this::onLoadQrCode);
    }

    public void onLoadUnifiedKeyInfo(UnifiedKeyInfo unifiedKeyInfo) {
        if (unifiedKeyInfo == null) {
            return;
        }

        this.unifiedKeyInfo = unifiedKeyInfo;

        final String fingerprint = KeyFormattingUtils.convertFingerprintToHex(unifiedKeyInfo.fingerprint());
        mFingerprintView.setText(KeyFormattingUtils.formatFingerprint(fingerprint));
    }

    private void onLoadQrCode(Bitmap qrCode) {
        if (mQrCodeBitmapCache != null) {
            return;
        }

        mQrCodeBitmapCache = qrCode;
        if (ViewKeyAdvShareFragment.this.isAdded()) {
            mQrCode.requestLayout();

            // simple fade-in animation
            AlphaAnimation anim = new AlphaAnimation(0.0f, 1.0f);
            anim.setDuration(200);
            mQrCode.startAnimation(anim);
        }
    }

    private void uploadToKeyserver() {
        Intent uploadIntent = new Intent(getActivity(), UploadKeyActivity.class);
        uploadIntent.setData(KeyRings.buildUnifiedKeyRingUri(unifiedKeyInfo.master_key_id()));
        uploadIntent.putExtra(MultiUserIdsFragment.EXTRA_KEY_IDS, new long[]{ unifiedKeyInfo.master_key_id() });
        startActivityForResult(uploadIntent, 0);
    }


}
