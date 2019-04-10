/*
 * *************************************************************************
 *  PlaylistActivity.java
 * **************************************************************************
 *  Copyright © 2017 VLC authors and VideoLAN
 *  Author: Geoffrey Métais
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.vlc.gui;

import android.annotation.TargetApi;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

import org.jetbrains.annotations.NotNull;
import org.videolan.medialibrary.Medialibrary;
import org.videolan.medialibrary.media.MediaLibraryItem;
import org.videolan.medialibrary.media.MediaWrapper;
import org.videolan.medialibrary.media.Playlist;
import org.videolan.vlc.BuildConfig;
import org.videolan.vlc.R;
import org.videolan.vlc.VLCApplication;
import org.videolan.vlc.databinding.PlaylistActivityBinding;
import org.videolan.vlc.gui.audio.AudioBrowserAdapter;
import org.videolan.vlc.gui.audio.AudioBrowserFragment;
import org.videolan.vlc.gui.dialogs.ContextSheetKt;
import org.videolan.vlc.gui.dialogs.CtxActionReceiver;
import org.videolan.vlc.gui.dialogs.SavePlaylistDialog;
import org.videolan.vlc.gui.helpers.AudioUtil;
import org.videolan.vlc.gui.helpers.FloatingActionButtonBehavior;
import org.videolan.vlc.gui.helpers.SwipeDragItemTouchHelperCallback;
import org.videolan.vlc.gui.helpers.UiTools;
import org.videolan.vlc.interfaces.IEventsHandler;
import org.videolan.vlc.interfaces.IListEventsHandler;
import org.videolan.vlc.media.MediaUtils;
import org.videolan.vlc.media.PlaylistManager;
import org.videolan.vlc.util.AndroidDevices;
import org.videolan.vlc.util.Constants;
import org.videolan.vlc.util.FileUtils;
import org.videolan.vlc.util.Util;
import org.videolan.vlc.util.WorkersKt;
import org.videolan.vlc.viewmodels.paged.MLPagedModel;
import org.videolan.vlc.viewmodels.paged.PagedTracksModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import androidx.annotation.Nullable;
import androidx.appcompat.view.ActionMode;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import androidx.paging.PagedList;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import kotlinx.coroutines.Job;

public class PlaylistActivity extends AudioPlayerContainerActivity implements IEventsHandler, IListEventsHandler, ActionMode.Callback, View.OnClickListener, CtxActionReceiver {

    public final static String TAG = "VLC/PlaylistActivity";
    public final static String TAG_FAB_VISIBILITY = "FAB";

    private AudioBrowserAdapter mAdapter;
    private MediaLibraryItem mPlaylist;
    private Medialibrary mMediaLibrary = VLCApplication.getMLInstance();
    private PlaylistActivityBinding mBinding;
    private ActionMode mActionMode;
    private boolean mIsPlaylist;
    private PagedTracksModel tracksModel;
    private ItemTouchHelper mItemTouchHelper;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mBinding = DataBindingUtil.setContentView(this, R.layout.playlist_activity);

        initAudioPlayerContainerActivity();
        setFragmentContainer(mBinding.songs);
        setOriginalBottomPadding(getFragmentContainer().getPaddingBottom());
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        mPlaylist = (MediaLibraryItem) (savedInstanceState != null ?
                savedInstanceState.getParcelable(AudioBrowserFragment.TAG_ITEM) :
                getIntent().getParcelableExtra(AudioBrowserFragment.TAG_ITEM));
        mIsPlaylist = mPlaylist.getItemType() == MediaLibraryItem.TYPE_PLAYLIST;
        mBinding.setPlaylist(mPlaylist);
        tracksModel = ViewModelProviders.of(this, new PagedTracksModel.Factory(this, mPlaylist)).get(PagedTracksModel.class);
        ((MLPagedModel) tracksModel).getPagedList().observe(this, new Observer<PagedList<MediaLibraryItem>>() {
            @Override
            public void onChanged(@Nullable PagedList<MediaLibraryItem> tracks) {
                if (tracks != null) {
                    if (tracks.isEmpty() && !tracksModel.isFiltering()) finish();
                    else mAdapter.submitList(tracks);
                }
            }
        });
        mAdapter = new AudioBrowserAdapter(MediaLibraryItem.TYPE_MEDIA, this, this, mIsPlaylist);
        mItemTouchHelper = new ItemTouchHelper(new SwipeDragItemTouchHelperCallback(mAdapter));
        mItemTouchHelper.attachToRecyclerView(mBinding.songs);

        mBinding.songs.setLayoutManager(new LinearLayoutManager(this));
        mBinding.songs.setAdapter(mAdapter);
        final boolean fabVisibility = savedInstanceState != null && savedInstanceState.getBoolean(TAG_FAB_VISIBILITY);

        if (!TextUtils.isEmpty(mPlaylist.getArtworkMrl())) {
            WorkersKt.runIO(new Runnable() {
                @Override
                public void run() {
                    final Bitmap cover = AudioUtil.readCoverBitmap(Uri.decode(mPlaylist.getArtworkMrl()), getResources().getDimensionPixelSize(R.dimen.audio_browser_item_size));
                    if (cover != null) {
                        mBinding.setCover(new BitmapDrawable(PlaylistActivity.this.getResources(), cover));
                        WorkersKt.runOnMainThread(new Runnable() {
                            @Override
                            public void run() {
                                mBinding.appbar.setExpanded(true, true);
                                if (savedInstanceState != null) {
                                    if (fabVisibility) mBinding.fab.show();
                                    else mBinding.fab.hide();
                                }
                            }
                        });
                    } else WorkersKt.runOnMainThread(new Runnable() {
                        @Override
                        public void run() {
                            fabFallback();
                        }
                    });
                }
            });
        } else fabFallback();
        mBinding.fab.setOnClickListener(this);
    }

    private void fabFallback() {
        mBinding.appbar.setExpanded(false);
        final CoordinatorLayout.LayoutParams lp = (CoordinatorLayout.LayoutParams) mBinding.fab.getLayoutParams();
        lp.setAnchorId(R.id.songs);
        lp.anchorGravity = Gravity.BOTTOM | Gravity.END;
        lp.setBehavior(new FloatingActionButtonBehavior(PlaylistActivity.this, null));
        mBinding.fab.setLayoutParams(lp);
        mBinding.fab.show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopActionMode();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putParcelable(AudioBrowserFragment.TAG_ITEM, mPlaylist);
        outState.putBoolean(TAG_FAB_VISIBILITY, mBinding.fab.getVisibility() == View.VISIBLE);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onClick(View v, int position, MediaLibraryItem item) {
        if (mActionMode != null) {
            mAdapter.getMultiSelectHelper().toggleSelection(position);
            invalidateActionMode();
        } else MediaUtils.INSTANCE.playTracks(this, mPlaylist, position);
    }

    @Override
    public boolean onLongClick(View v, int position, MediaLibraryItem item) {
        if (mActionMode != null) return false;
        mAdapter.getMultiSelectHelper().toggleSelection(position);
        startActionMode();
        return true;
    }

    @Override
    public void onImageClick(@NotNull View v, int position, @NotNull MediaLibraryItem item) {
        if (mActionMode != null) {
            onClick(v, position, item);
            return;
        }
        onLongClick(v, position, item);
    }

    @Override
    public void onCtxClick(View anchor, final int position, final MediaLibraryItem mediaItem) {
        if (mActionMode == null)
            ContextSheetKt.showContext(this, this, position, mediaItem.getTitle(), Constants.CTX_PLAYLIST_ITEM_FLAGS);
    }

    @Override
    public void onUpdateFinished(RecyclerView.Adapter adapter) {
    }

    @Override
    public void onRemove(int position, @NotNull final MediaLibraryItem item) {
        ArrayList<MediaWrapper> tracks = new ArrayList<MediaWrapper>(Arrays.asList(item.getTracks()));
        removeFromPlaylist(tracks, new ArrayList(Arrays.asList(position)));
    }

    @Override
    public void onMove(int oldPosition, int newPosition) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Moving item from " + oldPosition + " to " + newPosition);
        ((Playlist) mPlaylist).move(oldPosition, newPosition);

    }

    @Override
    public void onStartDrag(@NotNull RecyclerView.ViewHolder viewHolder) {
        mItemTouchHelper.startDrag(viewHolder);
    }

    @Override
    protected void onPlayerStateChanged(View bottomSheet, int newState) {
        int visibility = mBinding.fab.getVisibility();
        if (visibility == View.VISIBLE && newState != BottomSheetBehavior.STATE_COLLAPSED && newState != BottomSheetBehavior.STATE_HIDDEN)
            mBinding.fab.setVisibility(View.INVISIBLE);
        else if (visibility == View.INVISIBLE && (newState == BottomSheetBehavior.STATE_COLLAPSED || newState == BottomSheetBehavior.STATE_HIDDEN))
            mBinding.fab.show();
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public void startActionMode() {
        mActionMode = startSupportActionMode(this);
    }

    protected void stopActionMode() {
        if (mActionMode != null) {
            mActionMode.finish();
            onDestroyActionMode(mActionMode);
        }
    }

    public void invalidateActionMode() {
        if (mActionMode != null)
            mActionMode.invalidate();
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        mode.getMenuInflater().inflate(R.menu.action_mode_audio_browser, menu);
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        final int count = mAdapter.getMultiSelectHelper().getSelectionCount();
        if (count == 0) {
            stopActionMode();
            return false;
        }
        boolean isSong = count == 1 && mAdapter.getMultiSelectHelper().getSelection().get(0).getItemType() == MediaLibraryItem.TYPE_MEDIA;
        //menu.findItem(R.id.action_mode_audio_playlist_up).setVisible(isSong && mIsPlaylist);
        //menu.findItem(R.id.action_mode_audio_playlist_down).setVisible(isSong && mIsPlaylist);
        menu.findItem(R.id.action_mode_audio_set_song).setVisible(isSong && AndroidDevices.isPhone && !mIsPlaylist);
        menu.findItem(R.id.action_mode_audio_info).setVisible(isSong);
        menu.findItem(R.id.action_mode_audio_append).setVisible(PlaylistManager.Companion.hasMedia());
        menu.findItem(R.id.action_mode_audio_delete).setVisible(mIsPlaylist);
        return true;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        final List<MediaLibraryItem> list = mAdapter.getMultiSelectHelper().getSelection();
        final List<MediaWrapper> tracks = new ArrayList<>();
        for (MediaLibraryItem mediaItem : list)
            tracks.addAll(Arrays.asList(mediaItem.getTracks()));

        if (item.getItemId() == R.id.action_mode_audio_playlist_up) {
            Toast.makeText(this, "UP !",
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        if (item.getItemId() == R.id.action_mode_audio_playlist_down) {
            Toast.makeText(this, "DOWN !",
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        List<Integer> indexes = new ArrayList<Integer>();
        for (int i = 0; i < mAdapter.getMultiSelectHelper().getSelectionMap().size(); i++) {
            indexes.add(mAdapter.getMultiSelectHelper().getSelectionMap().keyAt(i));
        }

        stopActionMode();
        switch (item.getItemId()) {
            case R.id.action_mode_audio_play:
                MediaUtils.INSTANCE.openList(this, tracks, 0);
                break;
            case R.id.action_mode_audio_append:
                MediaUtils.INSTANCE.appendMedia(this, tracks);
                break;
            case R.id.action_mode_audio_add_playlist:
                UiTools.addToPlaylist(this, tracks);
                break;
            case R.id.action_mode_audio_info:
                showInfoDialog((MediaWrapper) list.get(0));
                break;
            case R.id.action_mode_audio_set_song:
                AudioUtil.setRingtone((MediaWrapper) list.get(0), this);
                break;
            case R.id.action_mode_audio_delete:
                removeFromPlaylist(tracks, indexes);
                break;
            default:
                return false;
        }
        return true;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        mActionMode = null;
        mAdapter.getMultiSelectHelper().clearSelection();
    }

    protected void showInfoDialog(MediaWrapper media) {
        final Intent i = new Intent(this, InfoActivity.class);
        i.putExtra(InfoActivity.TAG_ITEM, media);
        startActivity(i);
    }

    @Override
    public void onCtxAction(final int position, int option) {
        if (position >= mAdapter.getItemCount()) return;
        final MediaWrapper media = (MediaWrapper) mAdapter.getItem(position);
        if (media == null) return;
        switch (option) {
            case Constants.CTX_INFORMATION:
                showInfoDialog(media);
                break;
            case Constants.CTX_DELETE:
                final int resId = mIsPlaylist ? R.string.confirm_remove_from_playlist : R.string.confirm_delete;
                UiTools.snackerConfirm(mBinding.getRoot(), getString(resId, media.getTitle()), new Runnable() {
                    @Override
                    public void run() {
                        if (mIsPlaylist) ((Playlist) mPlaylist).remove(position);
                        else removeItem(position, media);
                    }
                });
                break;
            case Constants.CTX_APPEND:
                MediaUtils.INSTANCE.appendMedia(this, media.getTracks());
                break;
            case Constants.CTX_PLAY_NEXT:
                MediaUtils.INSTANCE.insertNext(this, media.getTracks());
                break;
            case Constants.CTX_ADD_TO_PLAYLIST:
                UiTools.addToPlaylist(this, media.getTracks(), SavePlaylistDialog.KEY_NEW_TRACKS);
                break;
            case Constants.CTX_SET_RINGTONE:
                AudioUtil.setRingtone(media, this);
                break;
        }

    }

    private void removeItem(final int position, final MediaWrapper media) {
        final int resId = mIsPlaylist ? R.string.confirm_remove_from_playlist : R.string.confirm_delete;
        if (mIsPlaylist) {
            UiTools.snackerConfirm(mBinding.getRoot(), getString(resId, media.getTitle()), new Runnable() {
                @Override
                public void run() {
                    ((Playlist) mPlaylist).remove(position);
                }
            });
        } else {
            final Runnable deleteAction = new Runnable() {
                @Override
                public void run() {
                    deleteMedia(media);
                }
            };
            UiTools.snackerConfirm(mBinding.getRoot(), getString(resId, media.getTitle()), new Runnable() {
                @Override
                public void run() {
                    if (Util.checkWritePermission(PlaylistActivity.this, media, deleteAction)) deleteAction.run();
                }
            });
        }
    }

    protected void deleteMedia(final MediaLibraryItem mw) {
        WorkersKt.runIO(new Runnable() {
            @Override
            public void run() {
                final LinkedList<String> foldersToReload = new LinkedList<>();
                for (MediaWrapper media : mw.getTracks()) {
                    String path = media.getUri().getPath();
                    String parentPath = FileUtils.getParent(path);
                    if (FileUtils.deleteFile(path) && media.getId() > 0L && !foldersToReload.contains(parentPath)) {
                        foldersToReload.add(parentPath);
                    } else
                        UiTools.snacker(mBinding.getRoot(), getString(R.string.msg_delete_failed, media.getTitle()));
                }
                for (String folder : foldersToReload) mMediaLibrary.reload(folder);
            }
        });
    }

    @Override
    public void onClick(View v) {
        MediaUtils.INSTANCE.playTracks(this, mPlaylist, 0);
    }

    private void removeFromPlaylist(final List<MediaWrapper> list, List<Integer> indexes) {
        final HashMap<Integer, Long> itemsRemoved = new HashMap<>();
        final Playlist playlist = (Playlist) this.mPlaylist;


        for (MediaLibraryItem mediaItem : list) {
            for (int i = 0; i < playlist.getTracks().length; i++) {
                if (playlist.getTracks()[i].getId() == mediaItem.getId()) {
                    itemsRemoved.put(i, mediaItem.getId());
                }
            }
        }
        for (Integer index : indexes) {
            playlist.remove(index);

        }

        UiTools.snackerWithCancel(mBinding.getRoot(), getString(R.string.removed_from_playlist_anonymous), null, new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<Integer, Long> removedItem : itemsRemoved.entrySet()) {
                    playlist.add(removedItem.getValue(), removedItem.getKey());
                }
            }
        });
    }
}
