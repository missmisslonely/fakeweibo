/*
 * The MIT License (MIT)
 * Copyright (c) 2014 longkai
 * The software shall be used for good, not evil.
 */
package org.catnut.plugin.zhihu;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.BaseColumns;
import org.catnut.R;
import org.catnut.core.CatnutApp;
import org.catnut.core.CatnutMetadata;
import org.catnut.core.CatnutProcessor;
import org.catnut.core.CatnutProvider;
import org.catnut.util.CatnutUtils;
import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.regex.Matcher;

/**
 * 知乎的一条精选回答条目
 *
 * @author longkai
 */
public class Zhihu implements CatnutMetadata<JSONArray, ContentValues> {

	public static final String TABLE = "zhihus";
	public static final String SINGLE = "zhihu";
	public static final String MULTIPLE = "zhihus";

	public static final String CACHE_IMAGE_LOCATION = "plugins/zhihu";
	public static final Zhihu METADATA = new Zhihu();

	private Zhihu() {
	}

	// 问题相关
	/** id */
	public static final String QUESTION_ID = "question_id";
	/** 标题 */
	public static final String TITLE = "title";
	/** 描述 */
	public static final String DESCRIPTION = "description";

	// 回答相关
	/** id */
	public static final String ANSWER_ID = "answer_id";
	/** 回答内容 */
	public static final String ANSWER = "answer";
	/** 答案最后更新时间戳(需要*1000) */
	public static final String LAST_ALTER_DATE = "last_alter_date";

	// 回答者相关
	/** id */
	public static final String UID = "uid";
	/** 昵称 */
	public static final String NICK = "nick";
	/** 个性描述 */
	public static final String STATUS = "status";
	/** 头像 */
	public static final String AVATAR = "avatar";

	// 自定义
	/** 是否读过 */
	public static final String HAS_READ = "has_read";

	@Override
	public String ddl() {
		StringBuilder sql = new StringBuilder();
		// 阅读项目数据表
		// _id 仅做为本地的一个标识与列表顺序比较的时间戳，实际的主键还是答案的id
		sql.append("CREATE TABLE ").append(TABLE).append("(")
				.append(BaseColumns._ID).append(" int,")
				.append(QUESTION_ID).append(" int,")
				.append(TITLE).append(" text,")
				.append(DESCRIPTION).append(" text,")

				.append(ANSWER_ID).append(" int primary key,")
				.append(ANSWER).append(" text,")
				.append(LAST_ALTER_DATE).append(" int,")

				.append(UID).append(" text,")
				.append(NICK).append(" text,")
				.append(STATUS).append(" text,")
				.append(AVATAR).append(" text,")
				.append(HAS_READ).append(" int")
				.append(")");
		return sql.toString();
	}

	@Override
	public ContentValues convert(JSONArray array) {
		long now = System.currentTimeMillis();

		ContentValues item = new ContentValues();

		// 用当前时间戳来作为item在本地的标识与排序，降序，时间戳越大代表越新
		item.put(BaseColumns._ID, now);

		// 答案相关
		item.put(STATUS, array.optString(1));
		item.put(ANSWER, array.optString(2));
		item.put(LAST_ALTER_DATE, array.optLong(4) * 1000);
		item.put(ANSWER_ID, array.optLong(5));

		// 答主相关
		JSONArray user = array.optJSONArray(6);
		if (user != null) {
			item.put(NICK, user.optString(0));
			item.put(UID, user.optString(1));
			item.put(AVATAR, user.optInt(2));
		}

		// 问题相关
		JSONArray question = array.optJSONArray(7);
		if (question != null) {
			item.put(TITLE, question.optString(1, null));
			item.put(DESCRIPTION, question.optString(2));
			item.put(QUESTION_ID, question.optLong(3));
		}

		return item;
	}

	/**
	 * page == 1 表最新
	 *
	 * @param page which page?
	 * @return url
	 */
	public static String fetchUrl(int page) {
		return "http://www.zhihu.com/reader/json/"
				+ page + "?r=" + System.currentTimeMillis();
	}

	/**
	 * 获取本地缓存的图片地址
	 *
	 * @param context
	 * @param uri
	 * @return
	 */
	public static Uri getCacheImageLocation(Context context, Uri uri) {
		File img = new File(context.getExternalCacheDir() + File.separator
				+ CACHE_IMAGE_LOCATION + File.separator + uri.getLastPathSegment());
		return Uri.fromFile(img);
	}

	/**
	 * 每日精选处理器
	 *
	 * @author longkai
	 */
	public static class ZhihuProcessor implements CatnutProcessor<JSONArray> {

		private static ZhihuProcessor processor;

		private ZhihuProcessor() {
		}

		public static ZhihuProcessor getProcessor() {
			if (processor == null) {
				processor = new ZhihuProcessor();
			}
			return processor;
		}

		@Override
		public void asyncProcess(Context context, JSONArray data) throws Exception {
			ContentValues[] items = new ContentValues[data.length()];
			ArrayList<String> images = new ArrayList<String>(); // 图片下载
			Matcher matcher;
			for (int i = 0; i < items.length; i++) {
				items[i] = METADATA.convert(data.optJSONArray(i));
				matcher = ZhihuItemFragment.HTML_IMG.matcher(items[i].getAsString(Zhihu.DESCRIPTION));
				while (matcher.find()) {
					String group = matcher.group(1);
					images.add(group);
				}
				matcher = ZhihuItemFragment.HTML_IMG.matcher(items[i].getAsString(Zhihu.ANSWER));
				while (matcher.find()) {
					String group = matcher.group(1);
					images.add(group);
				}
			}
			context.getContentResolver().bulkInsert(CatnutProvider.parse(MULTIPLE), items);
			if (CatnutApp.getBoolean(R.string.pref_enable_cache_zhihu_images, R.bool.default_plugin_status)) {
				try {
					String location = CatnutUtils.mkdir(context, Zhihu.CACHE_IMAGE_LOCATION);
					Intent intent = new Intent(context, ImagesDownloader.class);
					intent.putExtra(ImagesDownloader.LOCATION, location);
					intent.putStringArrayListExtra(ImagesDownloader.URLS, images);
					context.startService(intent);
				} catch (Exception e) {
					// no-op, just quit download the images...
				}
			}
		}
	}
}
