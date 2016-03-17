package net.osmand.plus.download.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.view.ActionMode;
import android.support.v7.widget.PopupMenu;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.ExpandableListView.ExpandableListContextMenuInfo;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import net.osmand.IndexConstants;
import net.osmand.access.AccessibleToast;
import net.osmand.plus.ContextMenuAdapter;
import net.osmand.plus.ContextMenuAdapter.OnContextMenuClick;
import net.osmand.plus.IconsCache;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandPlugin;
import net.osmand.plus.R;
import net.osmand.plus.activities.LocalIndexHelper;
import net.osmand.plus.activities.LocalIndexHelper.LocalIndexType;
import net.osmand.plus.activities.LocalIndexInfo;
import net.osmand.plus.activities.OsmandBaseExpandableListAdapter;
import net.osmand.plus.base.OsmandExpandableListFragment;
import net.osmand.plus.dialogs.DirectionsDialogs;
import net.osmand.plus.download.DownloadActivity;
import net.osmand.plus.download.DownloadActivityType;
import net.osmand.plus.download.DownloadIndexesThread.DownloadEvents;
import net.osmand.plus.download.IndexItem;
import net.osmand.plus.helpers.FileNameTranslationHelper;
import net.osmand.plus.resources.IncrementalChangesManager;
import net.osmand.plus.resources.IncrementalChangesManager.IncrementalUpdate;
import net.osmand.plus.resources.IncrementalChangesManager.IncrementalUpdateList;
import net.osmand.util.Algorithms;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class LocalIndexesFragment extends OsmandExpandableListFragment implements DownloadEvents {

	private LoadLocalIndexTask asyncLoader;
	private Map<String, IndexItem> filesToUpdate = new HashMap<String, IndexItem>();
	private LocalIndexesAdapter listAdapter;
	private AsyncTask<LocalIndexInfo, ?, ?> operationTask;

	private boolean selectionMode = false;
	private Set<LocalIndexInfo> selectedItems = new LinkedHashSet<LocalIndexInfo>();

	protected static int DELETE_OPERATION = 1;
	protected static int BACKUP_OPERATION = 2;
	protected static int RESTORE_OPERATION = 3;

	private ContextMenuAdapter optionsMenuAdapter;
	private ActionMode actionMode;

	Drawable sdcard;
	Drawable planet;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.local_index, container, false);

		getDownloadActivity().setSupportProgressBarIndeterminateVisibility(false);

		ExpandableListView listView = (ExpandableListView) view.findViewById(android.R.id.list);
		listAdapter = new LocalIndexesAdapter(getDownloadActivity());
		listView.setAdapter(listAdapter);
		expandAllGroups();
		setListView(listView);
		colorDrawables();
		return view;
	}

	@SuppressWarnings({"unchecked", "deprecation"})
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (asyncLoader == null || asyncLoader.getResult() == null) {
			// getLastNonConfigurationInstance method should be in onCreate() method
			// (onResume() doesn't work)
			Object indexes = getActivity().getLastNonConfigurationInstance();
			if (indexes instanceof List<?>) {
				asyncLoader = new LoadLocalIndexTask();
				asyncLoader.setResult((List<LocalIndexInfo>) indexes);
			}
		}
		setHasOptionsMenu(true);
	}

	private void colorDrawables() {
		boolean light = getMyApplication().getSettings().isLightContent();
		sdcard = getActivity().getResources().getDrawable(R.drawable.ic_sdcard);
		sdcard.mutate();
		sdcard.setColorFilter(getActivity().getResources().getColor(R.color.color_distance), PorterDuff.Mode.MULTIPLY);
	}

	@Override
	public void onResume() {
		super.onResume();
		if (asyncLoader == null || asyncLoader.getResult() == null) {
			reloadData();
		}

		getExpandableListView().setOnCreateContextMenuListener(new View.OnCreateContextMenuListener() {
			@Override
			public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
				long packedPos = ((ExpandableListContextMenuInfo) menuInfo).packedPosition;
				int group = ExpandableListView.getPackedPositionGroup(packedPos);
				int child = ExpandableListView.getPackedPositionChild(packedPos);
				if (child >= 0 && group >= 0) {
					final LocalIndexInfo point = listAdapter.getChild(group, child);
					showContextMenu(point);
				}
			}
		});
	}

	public void reloadData() {
		List<IndexItem> itemsToUpdate = getDownloadActivity().getDownloadThread().getIndexes().getItemsToUpdate();
		filesToUpdate.clear();
		for(IndexItem ii : itemsToUpdate) {
			filesToUpdate.put(ii.getTargetFileName(), ii);
		}
		LoadLocalIndexTask current = asyncLoader;
		if(current == null || current.getStatus() == AsyncTask.Status.FINISHED ||
				current.isCancelled() || current.getResult() != null) {
			asyncLoader = new LoadLocalIndexTask();
			asyncLoader.execute();
		}
	}

	private void showContextMenu(final LocalIndexInfo info) {
		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
		final ContextMenuAdapter adapter = new ContextMenuAdapter(getActivity());
		basicFileOperation(info, adapter);
		OsmandPlugin.onContextMenuActivity(getActivity(), null, info, adapter);

		String[] values = adapter.getItemNames();
		builder.setItems(values, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				OnContextMenuClick clk = adapter.getClickAdapter(which);
				if (clk != null) {
					clk.onContextMenuClick(null, adapter.getElementId(which), which, false);
				}
			}

		});
		builder.show();
	}


	private void basicFileOperation(final LocalIndexInfo info, ContextMenuAdapter adapter) {
		OnContextMenuClick listener = new OnContextMenuClick() {
			@Override
			public boolean onContextMenuClick(ArrayAdapter<?> adapter, int resId, int pos, boolean isChecked) {
				return performBasicOperation(resId, info);
			}
		};
		if (info.getType() == LocalIndexType.MAP_DATA || info.getType() == LocalIndexType.SRTM_DATA ||
				info.getType() == LocalIndexType.WIKI_DATA) {
			if (!info.isBackupedData()) {
				adapter.item(R.string.local_index_mi_backup).listen(listener).position(1).reg();
			}
		}
		if (info.isBackupedData()) {
			adapter.item(R.string.local_index_mi_restore).listen(listener).position(2).reg();
		}
		if (info.getType() != LocalIndexType.TTS_VOICE_DATA && info.getType() != LocalIndexType.VOICE_DATA) {
			adapter.item(R.string.shared_string_rename).listen(listener).position(3).reg();
		}
		adapter.item(R.string.shared_string_delete).listen(listener).position(4).reg();
	}

	private boolean performBasicOperation(int resId, final LocalIndexInfo info) {
		if (resId == R.string.shared_string_rename) {
			renameFile(getActivity(), new File(info.getPathToData()), new Runnable() {

				@Override
				public void run() {
					getDownloadActivity().reloadLocalIndexes();
				}
			});
		} else if (resId == R.string.local_index_mi_restore) {
			new LocalIndexOperationTask(getDownloadActivity(), listAdapter, LocalIndexOperationTask.RESTORE_OPERATION).execute(info);
		} else if (resId == R.string.shared_string_delete) {
			AlertDialog.Builder confirm = new AlertDialog.Builder(getActivity());
			confirm.setPositiveButton(R.string.shared_string_yes, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					new LocalIndexOperationTask(getDownloadActivity(), listAdapter, LocalIndexOperationTask.DELETE_OPERATION).execute(info);
				}
			});
			confirm.setNegativeButton(R.string.shared_string_no, null);
			String fn = FileNameTranslationHelper.getFileName(getActivity(),
					getMyApplication().getResourceManager().getOsmandRegions(),
					info.getFileName());
			confirm.setMessage(getString(R.string.delete_confirmation_msg, fn));
			confirm.show();
		} else if (resId == R.string.local_index_mi_backup) {
			new LocalIndexOperationTask(getDownloadActivity(), listAdapter, LocalIndexOperationTask.BACKUP_OPERATION).execute(info);
		}
		return true;
	}

	public static void renameFile(final Activity a, final File f, final Runnable callback) {
		AlertDialog.Builder b = new AlertDialog.Builder(a);
		if (f.exists()) {
			int xt = f.getName().lastIndexOf('.');
			final String ext = xt == -1 ? "" : f.getName().substring(xt);
			final String originalName = xt == -1 ? f.getName() : f.getName().substring(0, xt);
			final EditText editText = new EditText(a);
			editText.setText(originalName);
			b.setView(editText);
			b.setPositiveButton(R.string.shared_string_save, new DialogInterface.OnClickListener() {

				@Override
				public void onClick(DialogInterface dialog, int which) {
					String newName = editText.getText().toString() + ext;
					File dest = new File(f.getParentFile(), newName);
					if (dest.exists()) {
						AccessibleToast.makeText(a, R.string.file_with_name_already_exists, Toast.LENGTH_LONG).show();
					} else {
						if (!dest.getParentFile().exists()) {
							dest.getParentFile().mkdirs();
						}
						if (f.renameTo(dest)) {
							if (callback != null) {
								callback.run();
							}
						} else {
							AccessibleToast.makeText(a, R.string.file_can_not_be_renamed, Toast.LENGTH_LONG).show();
						}
					}

				}
			});
			b.setNegativeButton(R.string.shared_string_cancel, null);
			b.show();
		}
	}


	public class LoadLocalIndexTask extends AsyncTask<Void, LocalIndexInfo, List<LocalIndexInfo>>
			implements AbstractLoadLocalIndexTask {

		private List<LocalIndexInfo> result;

		@Override
		protected List<LocalIndexInfo> doInBackground(Void... params) {
			LocalIndexHelper helper = new LocalIndexHelper(getMyApplication());
			return helper.getLocalIndexData(this);
		}

		@Override
		public void loadFile(LocalIndexInfo... loaded) {
			publishProgress(loaded);
		}

		@Override
		protected void onPreExecute() {
			getDownloadActivity().setSupportProgressBarIndeterminateVisibility(true);
			listAdapter.clear();
		}

		@Override
		protected void onProgressUpdate(LocalIndexInfo... values) {
			for (LocalIndexInfo v : values) {
				listAdapter.addLocalIndexInfo(v);
			}
			listAdapter.notifyDataSetChanged();
			expandAllGroups();
		}

		public void setResult(List<LocalIndexInfo> result) {
			this.result = result;
			listAdapter.clear();
			if (result != null) {
				for (LocalIndexInfo v : result) {
					listAdapter.addLocalIndexInfo(v);
				}
				listAdapter.notifyDataSetChanged();
				expandAllGroups();
				onPostExecute(result);
			}
		}

		@Override
		protected void onPostExecute(List<LocalIndexInfo> result) {
			this.result = result;
			listAdapter.sortData();
			if (getDownloadActivity() != null) {
				getDownloadActivity().setSupportProgressBarIndeterminateVisibility(false);
				getDownloadActivity().setLocalIndexInfos(result);
			}
		}

		public List<LocalIndexInfo> getResult() {
			return result;
		}

	}







	public static class LocalIndexOperationTask extends AsyncTask<LocalIndexInfo, LocalIndexInfo, String> {
		protected static int DELETE_OPERATION = 1;
		protected static int BACKUP_OPERATION = 2;
		protected static int RESTORE_OPERATION = 3;

		private final int operation;
		private DownloadActivity a;
		private LocalIndexesAdapter listAdapter;

		public LocalIndexOperationTask(DownloadActivity a, LocalIndexesAdapter listAdapter, int operation) {
			this.a = a;
			this.listAdapter = listAdapter;
			this.operation = operation;
		}

		private boolean move(File from, File to) {
			if (!to.getParentFile().exists()) {
				to.getParentFile().mkdirs();
			}
			return from.renameTo(to);
		}

		private File getFileToBackup(LocalIndexInfo i) {
			if (!i.isBackupedData()) {
				return new File(getMyApplication().getAppPath(IndexConstants.BACKUP_INDEX_DIR), i.getFileName());
			}
			return new File(i.getPathToData());
		}
		private OsmandApplication getMyApplication() {
			return (OsmandApplication) a.getApplication();
		}


		private File getFileToRestore(LocalIndexInfo i) {
			if (i.isBackupedData()) {
				File parent = new File(i.getPathToData()).getParentFile();
				if (i.getOriginalType() == LocalIndexType.MAP_DATA) {
					if (i.getFileName().endsWith(IndexConstants.BINARY_ROAD_MAP_INDEX_EXT)) {
						parent = getMyApplication().getAppPath(IndexConstants.ROADS_INDEX_DIR);
					} else {
						parent = getMyApplication().getAppPath(IndexConstants.MAPS_PATH);
					}
				} else if (i.getOriginalType() == LocalIndexType.TILES_DATA) {
					parent = getMyApplication().getAppPath(IndexConstants.TILES_INDEX_DIR);
				} else if (i.getOriginalType() == LocalIndexType.SRTM_DATA) {
					parent = getMyApplication().getAppPath(IndexConstants.SRTM_INDEX_DIR);
				} else if (i.getOriginalType() == LocalIndexType.WIKI_DATA) {
					parent = getMyApplication().getAppPath(IndexConstants.WIKI_INDEX_DIR);
				} else if (i.getOriginalType() == LocalIndexType.TTS_VOICE_DATA) {
					parent = getMyApplication().getAppPath(IndexConstants.VOICE_INDEX_DIR);
				} else if (i.getOriginalType() == LocalIndexType.VOICE_DATA) {
					parent = getMyApplication().getAppPath(IndexConstants.VOICE_INDEX_DIR);
				}
				return new File(parent, i.getFileName());
			}
			return new File(i.getPathToData());
		}
		@Override
		protected String doInBackground(LocalIndexInfo... params) {
			int count = 0;
			int total = 0;
			for (LocalIndexInfo info : params) {
				if (!isCancelled()) {
					boolean successfull = false;
					if (operation == DELETE_OPERATION) {
						File f = new File(info.getPathToData());
						successfull = Algorithms.removeAllFiles(f);
						if (successfull) {
							getMyApplication().getResourceManager().closeFile(info.getFileName());
						}
					} else if (operation == RESTORE_OPERATION) {
						successfull = move(new File(info.getPathToData()), getFileToRestore(info));
						if (successfull) {
							info.setBackupedData(false);
						}
					} else if (operation == BACKUP_OPERATION) {
						successfull = move(new File(info.getPathToData()), getFileToBackup(info));
						if (successfull) {
							info.setBackupedData(true);
							getMyApplication().getResourceManager().closeFile(info.getFileName());
						}
					}
					total++;
					if (successfull) {
						count++;
						publishProgress(info);
					}
				}
			}
			if (operation == DELETE_OPERATION) {
				a.getDownloadThread().updateLoadedFiles();
			}
			if (operation == DELETE_OPERATION) {
				return a.getString(R.string.local_index_items_deleted, count, total);
			} else if (operation == BACKUP_OPERATION) {
				return a.getString(R.string.local_index_items_backuped, count, total);
			} else if (operation == RESTORE_OPERATION) {
				return a.getString(R.string.local_index_items_restored, count, total);
			}

			return "";
		}


		@Override
		protected void onProgressUpdate(LocalIndexInfo... values) {
			if (listAdapter != null) {
				if (operation == DELETE_OPERATION) {
					listAdapter.delete(values);
				} else if (operation == BACKUP_OPERATION) {
					listAdapter.move(values, false);
				} else if (operation == RESTORE_OPERATION) {
					listAdapter.move(values, true);
				}
			}

		}

		@Override
		protected void onPreExecute() {
			a.setProgressBarIndeterminateVisibility(true);
		}

		@Override
		protected void onPostExecute(String result) {
			a.setProgressBarIndeterminateVisibility(false);
			AccessibleToast.makeText(a, result, Toast.LENGTH_LONG).show();
			if (operation == RESTORE_OPERATION || operation == BACKUP_OPERATION) {
				a.reloadLocalIndexes();
			} else {
				a.newDownloadIndexes();
			}
		}
	}

	@Override
	public void newDownloadIndexes() {
		reloadData();
	}

	@Override
	public void downloadHasFinished() {
		reloadData();
	}

	@Override
	public void downloadInProgress() {
	}

	@Override
	public boolean onChildClick(ExpandableListView parent, View v, int groupPosition, int childPosition, long id) {
		LocalIndexInfo child = listAdapter.getChild(groupPosition, childPosition);
		if (!selectionMode) {
			openPopUpMenu(v, child);
			return true;
		}
		selectedItems.add(child);
		listAdapter.notifyDataSetChanged();
		return true;
	}

	public Set<LocalIndexInfo> getSelectedItems() {
		return selectedItems;
	}


	@Override
	public void onPause() {
		super.onPause();
		if (operationTask != null) {
			operationTask.cancel(true);
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		asyncLoader.cancel(true);
	}


	@SuppressWarnings("deprecation")
	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		if (!this.isAdded()) {
			return;
		}

		//fixes issue when local files not shown after switching tabs
		//Next line throws NPE in some circumstances when called from dashboard and listAdpater=null is not checked for. (Checking !this.isAdded above is not sufficient!)
		if (listAdapter != null && listAdapter.getGroupCount() == 0 && getDownloadActivity().getLocalIndexInfos().size() > 0) {
			for (LocalIndexInfo info : getDownloadActivity().getLocalIndexInfos()) {
				listAdapter.addLocalIndexInfo(info);
			}
			listAdapter.sortData();
			getExpandableListView().setAdapter(listAdapter);
			expandAllGroups();
		}
		ActionBar actionBar = getDownloadActivity().getSupportActionBar();
		//hide action bar from downloadindexfragment
		actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
		optionsMenuAdapter = new ContextMenuAdapter(getDownloadActivity());
		OnContextMenuClick listener = new OnContextMenuClick() {
			@Override
			public boolean onContextMenuClick(ArrayAdapter<?> adapter, int itemId, int pos, boolean isChecked) {
				localOptionsMenu(itemId);
				return true;
			}
		};
		optionsMenuAdapter.item(R.string.local_index_mi_reload)
				.icon(R.drawable.ic_action_refresh_dark)
				.listen(listener).position(1).reg();
		optionsMenuAdapter.item(R.string.shared_string_delete)
				.icon(R.drawable.ic_action_delete_dark)
				.listen(listener).position(2).reg();
		optionsMenuAdapter.item(R.string.local_index_mi_backup)
				.listen(listener).position(3).reg();
		optionsMenuAdapter.item(R.string.local_index_mi_restore)
				.listen(listener).position(4).reg();
		// doesn't work correctly
		//int max =  getResources().getInteger(R.integer.abs__max_action_buttons);
		int max = 3;
		SubMenu split = null;
		for (int j = 0; j < optionsMenuAdapter.length(); j++) {
			MenuItem item;
			if (j + 1 >= max && optionsMenuAdapter.length() > max) {
				if (split == null) {
					split = menu.addSubMenu(0, 1, j + 1, R.string.shared_string_more_actions);
					split.setIcon(R.drawable.ic_overflow_menu_white);
					split.getItem();
					MenuItemCompat.setShowAsAction(split.getItem(), MenuItemCompat.SHOW_AS_ACTION_ALWAYS);
				}
				item = split.add(0, optionsMenuAdapter.getElementId(j), j + 1, optionsMenuAdapter.getItemName(j));
				MenuItemCompat.setShowAsAction(item, MenuItemCompat.SHOW_AS_ACTION_ALWAYS);
			} else {
				item = menu.add(0, optionsMenuAdapter.getElementId(j), j + 1, optionsMenuAdapter.getItemName(j));
				MenuItemCompat.setShowAsAction(item, MenuItemCompat.SHOW_AS_ACTION_ALWAYS);
			}
			OsmandApplication app = getMyApplication();
			if (optionsMenuAdapter.getImage(app, j, isLightActionBar()) != null) {
				item.setIcon(optionsMenuAdapter.getImage(app, j, isLightActionBar()));
			}

		}

		if (operationTask == null || operationTask.getStatus() == AsyncTask.Status.FINISHED) {
			menu.setGroupVisible(0, true);
		} else {
			menu.setGroupVisible(0, false);
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		for (int i = 0; i < optionsMenuAdapter.length(); i++) {
			if (itemId == optionsMenuAdapter.getElementId(i)) {
				optionsMenuAdapter.getClickAdapter(i).onContextMenuClick(null, itemId, i, false);
				return true;
			}
		}
		return super.onOptionsItemSelected(item);
	}

	public void doAction(int actionResId) {
		if (actionResId == R.string.local_index_mi_backup) {
			operationTask = new LocalIndexOperationTask(getDownloadActivity(), listAdapter, LocalIndexOperationTask.BACKUP_OPERATION);
		} else if (actionResId == R.string.shared_string_delete) {
			operationTask = new LocalIndexOperationTask(getDownloadActivity(), listAdapter, LocalIndexOperationTask.DELETE_OPERATION);
		} else if (actionResId == R.string.local_index_mi_restore) {
			operationTask = new LocalIndexOperationTask(getDownloadActivity(), listAdapter, LocalIndexOperationTask.RESTORE_OPERATION);
		} else {
			operationTask = null;
		}
		if (operationTask != null) {
			operationTask.execute(selectedItems.toArray(new LocalIndexInfo[selectedItems.size()]));
		}
		if (actionMode != null) {
			actionMode.finish();
		}
	}


	private void expandAllGroups() {
		for (int i = 0; i < listAdapter.getGroupCount(); i++) {
			getExpandableListView().expandGroup(i);
		}
	}

	private void openSelectionMode(final int actionResId, final int actionIconId,
								   final DialogInterface.OnClickListener listener) {
		String value = getString(actionResId);
		if (value.endsWith("...")) {
			value = value.substring(0, value.length() - 3);
		}
		final String actionButton = value;
		if (listAdapter.getGroupCount() == 0) {
			listAdapter.cancelFilter();
			expandAllGroups();
			listAdapter.notifyDataSetChanged();
			AccessibleToast.makeText(getDownloadActivity(), getString(R.string.local_index_no_items_to_do, actionButton.toLowerCase()), Toast.LENGTH_SHORT).show();
			return;
		}
		expandAllGroups();

		selectionMode = true;
		selectedItems.clear();
		actionMode = getDownloadActivity().startSupportActionMode(new ActionMode.Callback() {

			@Override
			public boolean onCreateActionMode(ActionMode mode, Menu menu) {
				selectionMode = true;
				MenuItem it = menu.add(actionResId);
				if (actionIconId != 0) {
					it.setIcon(actionIconId);
				}
				MenuItemCompat.setShowAsAction(it, MenuItemCompat.SHOW_AS_ACTION_IF_ROOM |
						MenuItemCompat.SHOW_AS_ACTION_WITH_TEXT);
				return true;
			}

			@Override
			public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
				return false;
			}

			@Override
			public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
				if (selectedItems.isEmpty()) {
					AccessibleToast.makeText(getDownloadActivity(),
							getString(R.string.local_index_no_items_to_do, actionButton.toLowerCase()), Toast.LENGTH_SHORT).show();
					return true;
				}

				AlertDialog.Builder builder = new AlertDialog.Builder(getDownloadActivity());
				builder.setMessage(getString(R.string.local_index_action_do, actionButton.toLowerCase(), selectedItems.size()));
				builder.setPositiveButton(actionButton, listener);
				builder.setNegativeButton(R.string.shared_string_cancel, null);
				builder.show();
				return true;
			}

			@Override
			public void onDestroyActionMode(ActionMode mode) {
				selectionMode = false;
				listAdapter.cancelFilter();
				expandAllGroups();
				listAdapter.notifyDataSetChanged();
			}

		});
		//findViewById(R.id.DescriptionText).setVisibility(View.GONE);
		listAdapter.notifyDataSetChanged();
	}

	public void localOptionsMenu(final int itemId) {
		if (itemId == R.string.local_index_mi_reload) {
			getDownloadActivity().reloadLocalIndexes();
		} else if (itemId == R.string.shared_string_delete) {
			openSelectionMode(itemId, R.drawable.ic_action_delete_dark,
					new DialogInterface.OnClickListener() {

						@Override
						public void onClick(DialogInterface dialog, int which) {
							doAction(itemId);
						}
					}, null);
		} else if (itemId == R.string.local_index_mi_backup) {
			openSelectionMode(itemId, R.drawable.ic_type_archive,
					new DialogInterface.OnClickListener() {

						@Override
						public void onClick(DialogInterface dialog, int which) {
							doAction(itemId);
						}
					}, EnumSet.of(LocalIndexType.MAP_DATA, LocalIndexType.WIKI_DATA, LocalIndexType.SRTM_DATA));
		} else if (itemId == R.string.local_index_mi_restore) {
			openSelectionMode(itemId, R.drawable.ic_type_archive,
					new DialogInterface.OnClickListener() {

						@Override
						public void onClick(DialogInterface dialog, int which) {
							doAction(itemId);
						}
					}, EnumSet.of(LocalIndexType.DEACTIVATED));
		}
	}

	public void openSelectionMode(int stringRes, int darkIcon, DialogInterface.OnClickListener listener, 
								  EnumSet<LocalIndexType> filter) {
		if (filter != null) {
			listAdapter.filterCategories(filter);
		}
		openSelectionMode(stringRes, darkIcon, listener);
	}




	protected class LocalIndexesAdapter extends OsmandBaseExpandableListAdapter {

		Map<LocalIndexInfo, List<LocalIndexInfo>> data = new LinkedHashMap<LocalIndexInfo, List<LocalIndexInfo>>();
		List<LocalIndexInfo> category = new ArrayList<LocalIndexInfo>();
		List<LocalIndexInfo> filterCategory = null;
		int warningColor;
		int okColor;
		int corruptedColor;
		DownloadActivity ctx;

		public LocalIndexesAdapter(DownloadActivity ctx) {
			this.ctx = ctx;
			warningColor = ctx.getResources().getColor(R.color.color_warning);
			okColor = ctx.getResources().getColor(R.color.color_ok);
			TypedArray ta = ctx.getTheme().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
			ta.recycle();
			corruptedColor = ctx.getResources().getColor(R.color.color_invalid);
		}

		public void clear() {
			data.clear();
			category.clear();
			filterCategory = null;
			notifyDataSetChanged();
		}

		public void sortData() {
			final Collator cl = Collator.getInstance();
			for (List<LocalIndexInfo> i : data.values()) {
				Collections.sort(i, new Comparator<LocalIndexInfo>() {
					@Override
					public int compare(LocalIndexInfo lhs, LocalIndexInfo rhs) {
						return cl.compare(getNameToDisplay(lhs), getNameToDisplay(rhs));
					}
				});
			}
		}

		public LocalIndexInfo findCategory(LocalIndexInfo val, boolean backuped) {
			for (LocalIndexInfo i : category) {
				if (i.isBackupedData() == backuped && val.getType() == i.getType() &&
						Algorithms.objectEquals(i.getSubfolder(), val.getSubfolder())) {
					return i;
				}
			}
			LocalIndexInfo newCat = new LocalIndexInfo(val.getType(), backuped, val.getSubfolder(),
					getMyApplication());
			category.add(newCat);
			data.put(newCat, new ArrayList<LocalIndexInfo>());
			return newCat;
		}

		public void delete(LocalIndexInfo[] values) {
			for (LocalIndexInfo i : values) {
				LocalIndexInfo c = findCategory(i, i.isBackupedData());
				if (c != null) {
					data.get(c).remove(i);
					if (data.get(c).size() == 0) {
						data.remove(c);
						category.remove(c);
					}
				}
			}
			notifyDataSetChanged();
		}

		public void move(LocalIndexInfo[] values, boolean oldBackupState) {
			for (LocalIndexInfo i : values) {
				LocalIndexInfo c = findCategory(i, oldBackupState);
				if (c != null) {
					data.get(c).remove(i);
				}
				c = findCategory(i, !oldBackupState);
				if (c != null) {
					data.get(c).add(i);
				}
			}
			notifyDataSetChanged();
			expandAllGroups();
		}

		public void cancelFilter() {
			filterCategory = null;
			notifyDataSetChanged();
		}

		public void filterCategories(EnumSet<LocalIndexType> types) {
			List<LocalIndexInfo> filter = new ArrayList<LocalIndexInfo>();
			List<LocalIndexInfo> source = filterCategory == null ? category : filterCategory;
			for (LocalIndexInfo info : source) {
				if(types.contains(info.getType())) {
					filter.add(info);
				}
			}
			filterCategory = filter;
			notifyDataSetChanged();
		}

		public void filterCategories(boolean backup) {
			List<LocalIndexInfo> filter = new ArrayList<LocalIndexInfo>();
			List<LocalIndexInfo> source = filterCategory == null ? category : filterCategory;
			for (LocalIndexInfo info : source) {
				if (info.isBackupedData() == backup) {
					filter.add(info);
				}
			}
			filterCategory = filter;
			notifyDataSetChanged();
		}

		public void addLocalIndexInfo(LocalIndexInfo info) {
			int found = -1;
			// search from end
			for (int i = category.size() - 1; i >= 0; i--) {
				LocalIndexInfo cat = category.get(i);
				if (cat.getType() == info.getType() && info.isBackupedData() == cat.isBackupedData() &&
						Algorithms.objectEquals(info.getSubfolder(), cat.getSubfolder())) {
					found = i;
					break;
				}
			}
			if (found == -1) {
				found = category.size();
				category.add(new LocalIndexInfo(info.getType(), info.isBackupedData(),
						info.getSubfolder(), getMyApplication()));
			}
			if (!data.containsKey(category.get(found))) {
				data.put(category.get(found), new ArrayList<LocalIndexInfo>());
			}
			data.get(category.get(found)).add(info);
		}

		@Override
		public LocalIndexInfo getChild(int groupPosition, int childPosition) {
			LocalIndexInfo cat = filterCategory != null ? filterCategory.get(groupPosition) : category.get(groupPosition);
			return data.get(cat).get(childPosition);
		}

		@Override
		public long getChildId(int groupPosition, int childPosition) {
			// it would be unusable to have 10000 local indexes
			return groupPosition * 10000 + childPosition;
		}

		@Override
		public View getChildView(final int groupPosition, final int childPosition,
								 boolean isLastChild, View convertView, ViewGroup parent) {
			LocalIndexInfoViewHolder viewHolder;
			if (convertView == null) {
				LayoutInflater inflater = LayoutInflater.from(ctx);
				convertView = inflater.inflate(R.layout.local_index_list_item, parent, false);
				viewHolder = new LocalIndexInfoViewHolder(convertView);
				convertView.setTag(viewHolder);
			} else {
				viewHolder = (LocalIndexInfoViewHolder) convertView.getTag();
			}
			viewHolder.bindLocalIndexInfo(getChild(groupPosition, childPosition));
			return convertView;
		}


		private String getNameToDisplay(LocalIndexInfo child) {
			String mapName = FileNameTranslationHelper.getFileName(ctx,
					ctx.getMyApplication().getResourceManager().getOsmandRegions(),
					child.getFileName());
			return mapName;
		}

		@Override
		public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
			View v = convertView;
			LocalIndexInfo group = getGroup(groupPosition);
			if (v == null) {
				LayoutInflater inflater = LayoutInflater.from(ctx);
				v = inflater.inflate(R.layout.download_item_list_section, parent, false);
			}
			StringBuilder name = new StringBuilder(group.getType().getHumanString(ctx));
			if (group.getSubfolder() != null) {
				name.append(" ").append(group.getSubfolder());
			}
			TextView nameView = ((TextView) v.findViewById(R.id.section_name));
			TextView sizeView = ((TextView) v.findViewById(R.id.section_description));
			List<LocalIndexInfo> list = data.get(group);
			int size = 0;
			for (LocalIndexInfo aList : list) {
				int sz = aList.getSize();
				if (sz < 0) {
					size = 0;
					break;
				} else {
					size += sz;
				}
			}
			String sz = "";
			if (size > 0) {
				if (size > 1 << 20) {
					sz = DownloadActivity.formatGb.format(new Object[]{(float) size / (1 << 20)});
				} else {
					sz = DownloadActivity.formatMb.format(new Object[]{(float) size / (1 << 10)});
				}
			}
			sizeView.setText(sz);
			sizeView.setVisibility(View.VISIBLE);
			nameView.setText(name.toString());

			v.setOnClickListener(null);

			TypedValue typedValue = new TypedValue();
			Resources.Theme theme = ctx.getTheme();
			theme.resolveAttribute(R.attr.ctx_menu_info_view_bg, typedValue, true);
			v.setBackgroundColor(typedValue.data);
			return v;
		}

		@Override
		public int getChildrenCount(int groupPosition) {
			LocalIndexInfo cat = filterCategory != null ? filterCategory.get(groupPosition) : category.get(groupPosition);
			return data.get(cat).size();
		}

		@Override
		public LocalIndexInfo getGroup(int groupPosition) {
			return filterCategory == null ? category.get(groupPosition) : filterCategory.get(groupPosition);
		}

		@Override
		public int getGroupCount() {
			return filterCategory == null ? category.size() : filterCategory.size();
		}

		@Override
		public long getGroupId(int groupPosition) {
			return groupPosition;
		}

		@Override
		public boolean hasStableIds() {
			return false;
		}

		@Override
		public boolean isChildSelectable(int groupPosition, int childPosition) {
			return true;
		}


		private String getMapDescription(LocalIndexInfo child) {
			if (child.getType() == LocalIndexType.TILES_DATA) {
				return ctx.getString(R.string.online_map);
			} else if (child.getFileName().endsWith(IndexConstants.BINARY_ROAD_MAP_INDEX_EXT)) {
				return ctx.getString(R.string.download_roads_only_item);
			} else if (child.isBackupedData() && child.getFileName().endsWith(IndexConstants.BINARY_WIKI_MAP_INDEX_EXT)) {
				return ctx.getString(R.string.download_wikipedia_maps);
			} else if (child.isBackupedData() && child.getFileName().endsWith(IndexConstants.BINARY_SRTM_MAP_INDEX_EXT)) {
				return ctx.getString(R.string.download_srtm_maps);
			}
			return "";
		}

		private class LocalIndexInfoViewHolder {

			private final TextView nameTextView;
			private final ImageButton options;
			private final ImageView icon;
			private final TextView descriptionTextView;
			private final CheckBox checkbox;

			public LocalIndexInfoViewHolder(View view) {
				nameTextView = ((TextView) view.findViewById(R.id.nameTextView));
				options = (ImageButton) view.findViewById(R.id.options);
				icon = (ImageView) view.findViewById(R.id.icon);
				descriptionTextView = (TextView) view.findViewById(R.id.descriptionTextView);
				checkbox = (CheckBox) view.findViewById(R.id.check_local_index);
			}

			public void bindLocalIndexInfo(final LocalIndexInfo child) {

				options.setImageDrawable(ctx.getMyApplication().getIconsCache()
						.getContentIcon(R.drawable.ic_overflow_menu_white));
				options.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						openPopUpMenu(v, child);
					}
				});
				int colorId = filesToUpdate.containsKey(child.getFileName()) ? R.color.color_distance : R.color.color_ok;
				if (child.isBackupedData()) {
					colorId = R.color.color_unknown;
				}
				icon.setImageDrawable(getContentIcon(ctx, child.getType().getIconResource(), colorId));

				nameTextView.setText(getNameToDisplay(child));
				if (child.isNotSupported()) {
					nameTextView.setTextColor(warningColor);
				} else if (child.isCorrupted()) {
					nameTextView.setTextColor(corruptedColor);
				} else if (child.isLoaded()) {
					// users confused okColor here with "uptodate", so let's leave white (black in dark app theme) as "isLoaded"
					//nameTextView.setTextColor(okColor);
				}
				if (child.isBackupedData()) {
					nameTextView.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
				} else {
					nameTextView.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
				}
				StringBuilder builder = new StringBuilder();

				final String mapDescription = getMapDescription(child);
				if (mapDescription.length() > 0) {
					builder.append(mapDescription);
				}

				if (child.getSize() >= 0) {
					if(builder.length() > 0) {
						builder.append(" • ");
					}
					if (child.getSize() > 100) {
						builder.append(DownloadActivity.formatMb.format(new Object[]{(float) child.getSize() / (1 << 10)}));
					} else {
						builder.append(child.getSize()).append(" KB");
					}
				}

				if(!Algorithms.isEmpty(child.getDescription())){
					if(builder.length() > 0) {
						builder.append(" • ");
					}
					builder.append(child.getDescription());
				}
				descriptionTextView.setText(builder.toString());
				checkbox.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
				if (selectionMode) {
					icon.setVisibility(View.GONE);
					options.setVisibility(View.GONE);
					checkbox.setChecked(selectedItems.contains(child));
					checkbox.setOnClickListener(new View.OnClickListener() {

						@Override
						public void onClick(View v) {
							if (checkbox.isChecked()) {
								selectedItems.add(child);
							} else {
								selectedItems.remove(child);
							}
						}
					});

				} else {
					options.setVisibility(View.VISIBLE);
					icon.setVisibility(View.VISIBLE);
				}
			}

			private Drawable getContentIcon(DownloadActivity context, int resourceId) {
				return context.getMyApplication().getIconsCache().getContentIcon(resourceId);
			}
			
			private Drawable getContentIcon(DownloadActivity context, int resourceId, int colorId) {
				return context.getMyApplication().getIconsCache().getIcon(resourceId, colorId);
			}
		}

	}

	private void openPopUpMenu(View v, final LocalIndexInfo info) {
		IconsCache iconsCache = getMyApplication().getIconsCache();
		final PopupMenu optionsMenu = new PopupMenu(getActivity(), v);
		DirectionsDialogs.setupPopUpMenuIcon(optionsMenu);
		final boolean restore = info.isBackupedData();
		MenuItem item;
		if ((info.getType() == LocalIndexType.MAP_DATA) || (info.getType() == LocalIndexType.DEACTIVATED)) {
			item = optionsMenu.getMenu().add(restore ? R.string.local_index_mi_restore : R.string.local_index_mi_backup)
					.setIcon(iconsCache.getContentIcon(R.drawable.ic_type_archive));
			item.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
				@Override
				public boolean onMenuItemClick(MenuItem item) {
					performBasicOperation(restore ? R.string.local_index_mi_restore : R.string.local_index_mi_backup, info);
					return true;
				}
			});
		}

		item = optionsMenu.getMenu().add(R.string.shared_string_rename)
				.setIcon(iconsCache.getContentIcon(R.drawable.ic_action_edit_dark));
		item.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				performBasicOperation(R.string.shared_string_rename, info);
				return true;
			}
		});
		final IndexItem update = filesToUpdate.get(info.getFileName());
		if (update != null) {
			item = optionsMenu.getMenu().add(R.string.shared_string_download)
					.setIcon(iconsCache.getContentIcon(R.drawable.ic_action_import));
			item.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
				@Override
				public boolean onMenuItemClick(MenuItem item) {
					getDownloadActivity().startDownload(update);
					return true;
				}
			});
		}

		item = optionsMenu.getMenu().add(R.string.shared_string_delete)
				.setIcon(iconsCache.getContentIcon(R.drawable.ic_action_delete_dark));
		item.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				performBasicOperation(R.string.shared_string_delete, info);
				return true;
			}
		});
		

		optionsMenu.show();
	}

	private void runLiveUpdate(final LocalIndexInfo info) {
		final String fnExt = Algorithms.getFileNameWithoutExtension(new File(info.getFileName()));
		new AsyncTask<Object, Object, IncrementalUpdateList>() {

			protected void onPreExecute() {
				getDownloadActivity().setSupportProgressBarIndeterminateVisibility(true);

			}

			@Override
			protected IncrementalUpdateList doInBackground(Object... params) {
				IncrementalChangesManager cm = getMyApplication().getResourceManager().getChangesManager();
				return cm.getUpdatesByMonth(fnExt);
			}

			protected void onPostExecute(IncrementalUpdateList result) {
				getDownloadActivity().setSupportProgressBarIndeterminateVisibility(false);
				if (result.errorMessage != null) {
					Toast.makeText(getDownloadActivity(), result.errorMessage, Toast.LENGTH_SHORT).show();
				} else {
					List<IncrementalUpdate> ll = result.getItemsForUpdate();
					if (ll.isEmpty()) {
						Toast.makeText(getDownloadActivity(), R.string.no_updates_available, Toast.LENGTH_SHORT).show();
					} else {
						int i = 0;
						IndexItem[] is = new IndexItem[ll.size()];
						for (IncrementalUpdate iu : ll) {
							IndexItem ii = new IndexItem(iu.fileName, "Incremental update", iu.timestamp, iu.sizeText,
									iu.contentSize, iu.containerSize, DownloadActivityType.LIVE_UPDATES_FILE);
							is[i++] = ii;
						}
						getDownloadActivity().startDownload(is);
					}
				}

			}

		}.execute(new Object[]{fnExt});
	}


	private DownloadActivity getDownloadActivity() {
		return (DownloadActivity) getActivity();
	}
}
