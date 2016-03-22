/*
 * The MIT License (MIT)
 * Copyright (c) 2014 longkai
 * The software shall be used for good, not evil.
 */
package org.catnut.plugin.zhihu;

import android.app.Activity;
import android.app.Fragment;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.widget.SwipeRefreshLayout;
import android.text.Html;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.squareup.picasso.Picasso;
import org.catnut.R;
import org.catnut.core.CatnutApp;
import org.catnut.core.CatnutProvider;
import org.catnut.fragment.GalleryPagerFragment;
import org.catnut.ui.SingleFragmentActivity;
import org.catnut.util.CatnutUtils;
import org.catnut.util.ColorSwicher;
import org.catnut.util.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知乎条目
 *
 * @author longkai
 */
public class ZhihuItemFragment extends Fragment implements
		View.OnClickListener, SwipeRefreshLayout.OnRefreshListener {
	public static final String TAG = ZhihuItemFragment.class.getSimpleName();

	private static final String[] PROJECTION = new String[]{
			Zhihu.QUESTION_ID,
			Zhihu.ANSWER,
			Zhihu.DESCRIPTION,
			Zhihu.TITLE,
			Zhihu.LAST_ALTER_DATE,
			Zhihu.NICK,
	};

	public static final Pattern HTML_IMG = Pattern.compile("<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>");

	private static final int ACTION_VIEW_ON_WEB = 1;
	private static final int ACTION_VIEW_ALL_ON_WEB = 2;

	private Handler mHandler = new Handler();

	private SwipeRefreshLayout mSwipeRefreshLayout;

	private long mAnswerId;
	private long mQuestionId;

	private ArrayList<Uri> mImageUrls;

	public static ZhihuItemFragment getFragment(long id) {
		Bundle args = new Bundle();
		args.putLong(Constants.ID, id);
		ZhihuItemFragment fragment = new ZhihuItemFragment();
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		mAnswerId = getArguments().getLong(Constants.ID);
		setHasOptionsMenu(true);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.zhihu_item, container, false);

		mSwipeRefreshLayout = (SwipeRefreshLayout) view;
		ColorSwicher.injectColor(mSwipeRefreshLayout);
		mSwipeRefreshLayout.setOnRefreshListener(this);
		return view;
	}

	@Override
	public void onViewCreated(final View view, final Bundle savedInstanceState) {
		final TextView title = (TextView) view.findViewById(android.R.id.title);
		final TextView author = (TextView) view.findViewById(R.id.author);
		final TextView lastAlterDate = (TextView) view.findViewById(R.id.last_alter_date);

		registerForContextMenu(title);
		title.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				getActivity().openContextMenu(title);
			}
		});

		(new Thread(new Runnable() {
			@Override
			public void run() {
				Cursor cursor = getActivity().getContentResolver().query(
						CatnutProvider.parse(Zhihu.MULTIPLE),
						PROJECTION,
						Zhihu.ANSWER_ID + "=" + mAnswerId,
						null,
						null
				);
				if (cursor.moveToNext()) {
					mQuestionId = cursor.getLong(cursor.getColumnIndex(Zhihu.QUESTION_ID));
					final String _title = cursor.getString(cursor.getColumnIndex(Zhihu.TITLE));
					final String _question = cursor.getString(cursor.getColumnIndex(Zhihu.DESCRIPTION));
					final String _nick = cursor.getString(cursor.getColumnIndex(Zhihu.NICK));
					final String _content = cursor.getString(cursor.getColumnIndex(Zhihu.ANSWER));
					final long _lastAlterDate = cursor.getLong(cursor.getColumnIndex(Zhihu.LAST_ALTER_DATE));
					cursor.close();

					// answer
					Matcher matcher = HTML_IMG.matcher(_content);
					final List<String> contentSegment = new ArrayList<String>();
					processText(_content, matcher, contentSegment);

					// question
					matcher = HTML_IMG.matcher(_question);
					final List<String> questionSegment = new ArrayList<String>();
					processText(_question, matcher, questionSegment);
					new Handler(Looper.getMainLooper()).post(new Runnable() {
						@Override
						public void run() {
							title.setText(_title);
							getActivity().getActionBar().setSubtitle(_title);

							// 假设第一个是文本，即偶数文本，奇数图片
							int l = contentSegment.size() > 1 ? contentSegment.size() >> 1 : 0;
							l += questionSegment.size() > 1 ? questionSegment.size() >> 1 : 0;

							if (l > 0) {
								mImageUrls = new ArrayList<Uri>(l);
							}

							// 是否使用缓存的图片
							boolean useCachedImg = CatnutApp.getBoolean(R.string.pref_enable_cache_zhihu_images, R.bool.default_plugin_status);

							l = 0; // reset for reuse
							String text;
							int screenWidth = CatnutUtils.getScreenWidth(getActivity());
							int max = getActivity().getResources().getDimensionPixelSize(R.dimen.max_thumb_width);
							if (screenWidth > max) {
								screenWidth = max;
							}

							LayoutInflater inflater = LayoutInflater.from(getActivity());
							if (!TextUtils.isEmpty(_question)) {
								ViewGroup questionHolder = (ViewGroup) view.findViewById(R.id.question);
								for (int i = 0; i < questionSegment.size(); i++) {
									text = questionSegment.get(i);
									if (!TextUtils.isEmpty(text)) {
										if ((i & 1) == 0) {
											TextView section = (TextView) inflater.inflate(R.layout.zhihu_text, null);
											section.setTextSize(16);
											section.setTextColor(getResources().getColor(R.color.black50PercentColor));
											section.setText(Html.fromHtml(text));
											section.setMovementMethod(LinkMovementMethod.getInstance());
											CatnutUtils.removeLinkUnderline(section);
											questionHolder.addView(section);
										} else {
											ImageView imageView = getImageView();
											Uri uri = useCachedImg ? Zhihu.getCacheImageLocation(getActivity(), Uri.parse(text)) : Uri.parse(text);
											Picasso.with(getActivity())
													.load(uri)
													.centerCrop()
													.resize(screenWidth, (int) (Constants.GOLDEN_RATIO * screenWidth))
													.error(R.drawable.error)
													.into(imageView);
											imageView.setTag(l++); // for click
											imageView.setOnClickListener(ZhihuItemFragment.this);
											mImageUrls.add(uri);
											questionHolder.addView(imageView);
										}
									}
								}
							}

							Typeface typeface = CatnutUtils.getTypeface(
									CatnutApp.getTingtingApp().getPreferences(),
									getString(R.string.pref_customize_tweet_font),
									getString(R.string.default_typeface)
							);
							ViewGroup answerHolder = (ViewGroup) view.findViewById(R.id.answer);
							for (int i = 0; i < contentSegment.size(); i++) {
								text = contentSegment.get(i);
								if (!TextUtils.isEmpty(text)) {
									if ((i & 1) == 0) {
										TextView section = (TextView) inflater.inflate(R.layout.zhihu_text, null);
										section.setText(Html.fromHtml(text));
										CatnutUtils.setTypeface(section, typeface);
										CatnutUtils.removeLinkUnderline(section);
										section.setMovementMethod(LinkMovementMethod.getInstance());
										answerHolder.addView(section);
									} else {
										ImageView image = getImageView();
										Uri uri = useCachedImg ? Zhihu.getCacheImageLocation(getActivity(), Uri.parse(text)) : Uri.parse(text);
										Picasso.with(getActivity())
												.load(uri)
												.centerCrop()
												.resize(screenWidth, (int) (Constants.GOLDEN_RATIO * screenWidth))
												.error(R.drawable.error)
												.into(image);
										image.setTag(l++); // 方便点击事件
										image.setOnClickListener(ZhihuItemFragment.this);
										mImageUrls.add(uri);
										answerHolder.addView(image);
									}
								}
							}
							author.setText(_nick);
							lastAlterDate.setText(DateUtils.getRelativeTimeSpanString(_lastAlterDate));
							if (mSwipeRefreshLayout != null) {
								mSwipeRefreshLayout.setRefreshing(false);
							}
						}
					});
				} else {
					cursor.close();
				}
			}
		})).start();
	}

	private ImageView getImageView() {
		ImageView image = new ImageView(getActivity());
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT
		);
		lp.setMargins(0, 10, 0, 10);
		image.setLayoutParams(lp);
		image.setAdjustViewBounds(true);
		image.setOnTouchListener(new View.OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent event) {
				return CatnutUtils.imageOverlay(v, event);
			}
		});
		image.setOnClickListener(this);
		return image;
	}

	private void processText(String _content, Matcher matcher, List<String> contentSegment) {
		int start;
		int lastStart = 0;
		while (matcher.find()) {
			start = matcher.start();
			contentSegment.add(_content.substring(lastStart, start));
			lastStart = matcher.end();
			contentSegment.add(matcher.group(1));
		}
		// no image, fallback
		if (contentSegment.size() == 0) {
			contentSegment.add(_content);
		} else {
			// append tail
			if ((contentSegment.size() - 1 & 1) == 0) {
				contentSegment.add(null); // place holder...
			}
			contentSegment.add(_content.substring(lastStart));
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		getActivity().getActionBar().setTitle(getString(R.string.read_zhihu));
		// 标记已读
		new Thread(new MarkHasReadRunable(mAnswerId, getActivity(), true)).start();
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
		menu.add(Menu.NONE, ACTION_VIEW_ALL_ON_WEB, Menu.NONE, getString(R.string.view_all_answer));
		menu.add(Menu.NONE, ACTION_VIEW_ON_WEB, Menu.NONE, getString(R.string.zhihu_view_on_web));
		super.onCreateContextMenu(menu, v, menuInfo);
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		viewOutside(item.getItemId());
		return super.onContextItemSelected(item);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		menu.add(Menu.NONE, ACTION_VIEW_ALL_ON_WEB, Menu.NONE, getString(R.string.view_all_answer))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
		menu.add(Menu.NONE, ACTION_VIEW_ON_WEB, Menu.NONE, getString(R.string.zhihu_view_on_web))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		viewOutside(item.getItemId());
		return super.onOptionsItemSelected(item);
	}

	private void viewOutside(int which) {
		switch (which) {
			case ACTION_VIEW_ON_WEB:
				startActivity(new Intent(Intent.ACTION_VIEW,
						Uri.parse("http://www.zhihu.com/question/" + mQuestionId + "/answer/" + mAnswerId)));
				break;
			case ACTION_VIEW_ALL_ON_WEB:
				startActivity(new Intent(Intent.ACTION_VIEW,
						Uri.parse("http://www.zhihu.com/question/" + mQuestionId)));
				break;
			default:
				break;
		}
	}

	@Override
	public void onRefresh() {
		new Thread(new Runnable() {
			@Override
			public void run() {
				// just for fun~
				try {
					TimeUnit.MILLISECONDS.sleep(1500);
				} catch (InterruptedException e) {
				}
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						if (mSwipeRefreshLayout != null) {
							mSwipeRefreshLayout.setRefreshing(false);
						}
					}
				});
			}
		}).start();
	}

	@Override
	public void onClick(View v) {
		ImageView image = (ImageView) v;
		((ImageView) v).getDrawable().clearColorFilter();
		image.invalidate();
		Integer index = (Integer) v.getTag();
		Intent intent = SingleFragmentActivity.getIntent(getActivity(), SingleFragmentActivity.GALLERY);
		intent.putExtra(GalleryPagerFragment.CUR_INDEX, index);
		intent.putExtra(GalleryPagerFragment.URLS, mImageUrls);
		intent.putExtra(GalleryPagerFragment.TITLE, getString(R.string.view_photos));
		startActivity(intent);
	}

	/**
	 * 标记已读线程
	 */
	public static class MarkHasReadRunable implements Runnable {

		private long answerId;
		private Context context;
		private boolean hasRead;

		public MarkHasReadRunable(long answerId, Context context, boolean hasRead) {
			this.answerId = answerId;
			this.context = context;
			this.hasRead = hasRead;
		}

		@Override
		public void run() {
			ContentValues values = new ContentValues();
			values.put(Zhihu.HAS_READ, hasRead);
			context.getContentResolver().update(
					CatnutProvider.parse(Zhihu.MULTIPLE, answerId),
					values,
					null,
					null
			);
		}
	}
}
