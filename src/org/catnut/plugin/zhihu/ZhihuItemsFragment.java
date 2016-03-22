/*
 * The MIT License (MIT)
 * Copyright (c) 2014 longkai
 * The software shall be used for good, not evil.
 */
package org.catnut.plugin.zhihu;

import android.app.LoaderManager;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.BaseColumns;
import android.support.v4.widget.SwipeRefreshLayout;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AutoCompleteTextView;
import android.widget.CursorAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import org.catnut.R;
import org.catnut.core.CatnutAPI;
import org.catnut.core.CatnutApp;
import org.catnut.core.CatnutArrayRequest;
import org.catnut.core.CatnutProvider;
import org.catnut.support.app.SwipeRefreshListFragment;
import org.catnut.ui.PluginsActivity;
import org.catnut.util.CatnutUtils;
import org.catnut.util.ColorSwicher;
import org.catnut.util.Constants;
import org.json.JSONArray;

/**
 * 知乎列表界面
 *
 * @author longkai
 */
public class ZhihuItemsFragment extends SwipeRefreshListFragment implements
		AbsListView.OnScrollListener, LoaderManager.LoaderCallbacks<Cursor>, AdapterView.OnItemLongClickListener, TextWatcher, View.OnClickListener, SwipeRefreshLayout.OnRefreshListener {

	public static final String TAG = ZhihuItemsFragment.class.getSimpleName();
	private static final int PAGE_SIZE = 10; // 每次加载10条吧

	private static final String[] PROJECTION = new String[]{
			BaseColumns._ID,
			Zhihu.TITLE,
			Zhihu.HAS_READ,
			"substr(" + Zhihu.ANSWER + ",0,80) as " + Zhihu.ANSWER, // [0-80)子串
			Zhihu.ANSWER_ID,
			Zhihu.NICK
	};

	private View mSearchFrame;
	private View mClear;
	private AutoCompleteTextView mSearchView;
	private CursorAdapter mSearchAdapter;
	private LoaderManager.LoaderCallbacks<Cursor> mSearchLoader;

	private RequestQueue mRequestQueue;
	private ZhihuItemsAdapter mAdapter;

	// 当前items数目，有可能超过的哦
	private int mCount = PAGE_SIZE;
	// 本地items总数
	private int mTotal;

	// 载入本地items总数线程
	private Runnable mLoadTotalCount = new Runnable() {
		@Override
		public void run() {
			Cursor cursor = getActivity().getContentResolver().query(
					CatnutProvider.parse(Zhihu.MULTIPLE),
					Constants.COUNT_PROJECTION,
					null, null, null
			);
			if (cursor.moveToNext()) {
				mTotal = cursor.getInt(0);
			}
			cursor.close();

			if (mTotal == 0) {
				// 如果一条也没有，那么去load一回吧...
				new Handler(Looper.getMainLooper()).post(new Runnable() {
					@Override
					public void run() {
						setRefreshing(true);
						refresh();
					}
				});
			}
		}
	};

	public static ZhihuItemsFragment getFragment() {
		return new ZhihuItemsFragment();
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		mAdapter = new ZhihuItemsAdapter(getActivity());
		mRequestQueue = CatnutApp.getTingtingApp().getRequestQueue();
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		mSearchFrame = inflater.inflate(R.layout.zhihu_search, null);
		mSearchView = (AutoCompleteTextView) mSearchFrame.findViewById(R.id.zhihu_search);
		mClear = mSearchFrame.findViewById(R.id.clear);
		return super.onCreateView(inflater, container, savedInstanceState);
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		mSearchView.addTextChangedListener(this);
		mSearchView.setThreshold(1); // 1 word
		mClear.setOnClickListener(this);
		getListView().addHeaderView(mSearchFrame);
		setOnRefreshListener(this);
		ColorSwicher.injectColor(getSwipeRefreshLayout());
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		setListAdapter(mAdapter);
		setEmptyText(getString(R.string.zhihu_refresh_hint));
		getListView().setOnScrollListener(this);
		getListView().setOnItemLongClickListener(this);
		setRefreshing(true);
		if (CatnutApp.getBoolean(R.string.pref_enable_zhihu_auto_refresh, R.bool.default_plugin_status)) {
			refresh();
		} else {
			getLoaderManager().initLoader(0, null, this);
		}
		new Thread(mLoadTotalCount).start(); // 载入总数
	}

	@Override
	public void onListItemClick(ListView l, View v, int position, long id) {
		Cursor c = (Cursor) mAdapter.getItem(position - 1); // a header
		viewItem(c, id);
	}

	private void viewItem(Cursor c, long id) {
		long answer_id = c.getLong(c.getColumnIndex(Zhihu.ANSWER_ID));
		// 跳转
		PluginsActivity activity = (PluginsActivity) getActivity();
		if (CatnutApp.getBoolean(R.string.pref_enable_zhihu_pager, R.bool.default_plugin_status)) {
			Intent intent = new Intent(activity, PluginsActivity.class);
			intent.putExtra(PagerItemFragment.ORDER_ID, id);
			intent.putExtra(Constants.ID, answer_id);
			intent.putExtra(Constants.ACTION, PluginsActivity.ACTION_ZHIHU_PAGER);
			startActivity(intent);
		} else {
			Intent intent = new Intent(activity, PluginsActivity.class);
			intent.putExtra(Constants.ACTION, PluginsActivity.ACTION_ZHIHU_ITEM);
			intent.putExtra(Constants.ID, answer_id);
			startActivity(intent);
		}
	}

	@Override
	public void onScrollStateChanged(AbsListView view, int scrollState) {
		if (scrollState == SCROLL_STATE_IDLE
				&& getListView().getLastVisiblePosition() == mAdapter.getCount()
				&& mAdapter.getCount() < mTotal
				&& !isRefreshing()) {
			setRefreshing(true);
			mCount += PAGE_SIZE;
			getLoaderManager().restartLoader(0, null, this);
		}
	}

	@Override
	public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
		// no-op
	}

	@Override
	public Loader<Cursor> onCreateLoader(int id, Bundle args) {
		return new CursorLoader(
				getActivity(),
				CatnutProvider.parse(Zhihu.MULTIPLE),
				PROJECTION,
				null,
				null,
				BaseColumns._ID + " desc limit " + mCount
		);
	}

	@Override
	public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
		mAdapter.swapCursor(data);
		if (isRefreshing()) {
			setRefreshing(false);
		}
	}

	@Override
	public void onLoaderReset(Loader<Cursor> loader) {
		mAdapter.swapCursor(null);
	}

	@Override
	public void onRefresh() {
		refresh();
	}

	// 刷新
	private void refresh() {
		mRequestQueue.add(new CatnutArrayRequest(
				getActivity(),
				new CatnutAPI(Request.Method.GET, Zhihu.fetchUrl(1), false, null),
				Zhihu.ZhihuProcessor.getProcessor(),
				new Response.Listener<JSONArray>() {
					@Override
					public void onResponse(JSONArray response) {
						mCount = response.length();
						getLoaderManager().restartLoader(0, null, ZhihuItemsFragment.this);
						new Thread(mLoadTotalCount).start();
					}
				},
				new Response.ErrorListener() {
					@Override
					public void onErrorResponse(VolleyError error) {
						Toast.makeText(getActivity(), error.getLocalizedMessage(), Toast.LENGTH_LONG).show();
						setRefreshing(false);
					}
				}
		));
	}

	@Override
	public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
		Cursor c = (Cursor) mAdapter.getItem(position - 1); // a header
		new Thread(new ZhihuItemFragment.MarkHasReadRunable(
				c.getLong(c.getColumnIndex(Zhihu.ANSWER_ID)),
				getActivity(),
				c.getInt(c.getColumnIndex(Zhihu.HAS_READ)) != 1
		)).start();
		return true;
	}

	@Override
	public void beforeTextChanged(CharSequence s, int start, int count, int after) {
		// no-op
	}

	@Override
	public void onTextChanged(CharSequence s, int start, int before, int count) {
		// no-op
	}

	@Override
	public void afterTextChanged(Editable s) {
		String keywords = s.toString().trim();
		if (keywords.length() > 0) {
			if (mSearchAdapter == null) {
				initSearchLoader();
			}
			Bundle args = new Bundle();
			args.putString(Constants.KEYWORDS, keywords);
			getLoaderManager().restartLoader(1, args, mSearchLoader);
			mClear.setVisibility(View.VISIBLE);
		} else {
			mClear.setVisibility(View.GONE);
		}
	}

	private void initSearchLoader() {
		mSearchAdapter = new CursorAdapter(getActivity(), null, 0) {
			@Override
			public View newView(Context context, Cursor cursor, ViewGroup parent) {
				return LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_1, null);
			}

			@Override
			public CharSequence convertToString(Cursor cursor) {
				return cursor.getString(1);
			}

			@Override
			public void bindView(View view, Context context, Cursor cursor) {
				TextView tv = (TextView) view;
				tv.setText(cursor.getString(1));
			}
		};
		mSearchLoader = new LoaderManager.LoaderCallbacks<Cursor>() {
			@Override
			public Loader<Cursor> onCreateLoader(int id, Bundle args) {
				String keywords = args.getString(Constants.KEYWORDS);
				return new CursorLoader(
						getActivity(),
						CatnutProvider.parse(Zhihu.MULTIPLE),
						PROJECTION,
						new StringBuilder(Zhihu.TITLE)
								.append(" like ").append(CatnutUtils.like(keywords))
								.append(" or ").append(Zhihu.DESCRIPTION).append(" like ")
								.append(CatnutUtils.like(keywords)).toString(),
						null,
						Zhihu.LAST_ALTER_DATE + " desc"

				);
			}

			@Override
			public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
				mSearchAdapter.swapCursor(data);
			}

			@Override
			public void onLoaderReset(Loader<Cursor> loader) {
				mSearchAdapter.swapCursor(null);
			}
		};
		mSearchView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				Cursor c = (Cursor) mSearchAdapter.getItem(position);
				viewItem(c, id);
			}
		});
		mSearchView.setAdapter(mSearchAdapter);
	}

	@Override
	public void onClick(View v) {
		mSearchView.setText(null);
	}
}
