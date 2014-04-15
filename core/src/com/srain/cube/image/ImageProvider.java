package com.srain.cube.image;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build.VERSION_CODES;
import android.util.Log;

import com.srain.cube.file.DiskLruCache;
import com.srain.cube.image.drawable.RecyclingBitmapDrawable;
import com.srain.cube.image.iface.ImageMemoryCache;
import com.srain.cube.image.iface.ImageResizer;
import com.srain.cube.image.imple.DefaultMemoryCache;
import com.srain.cube.image.imple.LruImageFileCache;
import com.srain.cube.image.util.DefaultDownloader;
import com.srain.cube.image.util.Downloader;
import com.srain.cube.image.util.MarkableInputStream;
import com.srain.cube.util.CLog;
import com.srain.cube.util.Version;

/**
 * 
 * This class handles disk and memory caching of bitmaps.
 * 
 * Most of the code is taken from the Android best practice of displaying Bitmaps <a href="http://developer.android.com/training/displaying-bitmaps/index.html">Displaying Bitmaps Efficiently</a>.
 * 
 * @author http://www.liaohuqiu.net
 */
public class ImageProvider {

	protected static final boolean DEBUG = CLog.DEBUG_IMAGE;

	protected static final String TAG = "image_provider";

	private static final String MSG_FETCH_BEGIN = "%s fetchBitmapData";
	private static final String MSG_FETCH_BEGIN_IDENTITY_KEY = "%s identityKey: %s";
	private static final String MSG_FETCH_BEGIN_FILE_CACHE_KEY = "%s fileCacheKey: %s";
	private static final String MSG_FETCH_BEGIN_IDENTITY_URL = "%s identityUrl: %s";
	private static final String MSG_FETCH_BEGIN_ORIGIN_URL = "%s originUrl: %s";

	private static final String MSG_FETCH_TRY_REUSE = "%s Disk Cache not hit. Try to reuse";
	private static final String MSG_FETCH_HIT_DISK_CACHE = "%s Disk Cache hit";
	private static final String MSG_FETCH_REUSE_SUCC = "%s reuse size: %s";
	private static final String MSG_FETCH_REUSE_FAIL = "%s reuse fail: %s, %s";
	private static final String MSG_FETCH_DOWNLOAD = "%s downloading: %s";
	private static final String MSG_DECODE = "%s decode: %sx%s inSampleSize:%s";

	private ImageMemoryCache mMemoryCache;
	private LruImageFileCache mFileCache;

	private static ImageProvider sDefault;

    private static Downloader downloader;

    private final int MARKER = 65536;

	public static ImageProvider getDefault(Context context) {
		if (null == sDefault) {
			sDefault = new ImageProvider(context, DefaultMemoryCache.getDefault(context), LruImageFileCache.getDefault(context));
            downloader = new DefaultDownloader();
		}
		return sDefault;
	}

	public ImageProvider(Context context, ImageMemoryCache memoryCache, LruImageFileCache fileCache) {
		mMemoryCache = memoryCache;
		mFileCache = fileCache;
	}

	/**
	 * Create a BitmapDrawable which can be managed in ImageProvider
	 * 
	 * @param resources
	 * @param bitmap
	 * @return
	 */
	public BitmapDrawable createBitmapDrawable(Resources resources, Bitmap bitmap) {
		if (bitmap == null) {
			return null;
		}
		BitmapDrawable drawable = null;
		if (bitmap != null) {
			if (Version.hasHoneycomb()) {
				// Running on Honeycomb or newer, so wrap in a standard BitmapDrawable
				drawable = new BitmapDrawable(resources, bitmap);
			} else {
				// Running on Gingerbread or older, so wrap in a RecyclingBitmapDrawable
				// which will recycle automagically
				drawable = new RecyclingBitmapDrawable(resources, bitmap);
			}
		}
		return drawable;
	}

	/**
	 * Get from memory cache.
	 * 
	 * @param imageTask
	 *            Unique identifier for which item to get
	 * @return The bitmap drawable if found in cache, null otherwise
	 */

	public BitmapDrawable getBitmapFromMemCache(ImageTask imageTask) {
		BitmapDrawable memValue = null;

		if (mMemoryCache != null) {
			memValue = mMemoryCache.get(imageTask.getIdentityKey());
		}

		return memValue;
	}

	public void addBitmapToMemCache(String key, BitmapDrawable drawable) {

		// If the API level is lower than 11, do not use memory cache
		if (key == null || drawable == null || !Version.hasHoneycomb()) {
			return;
		}

		// Add to memory cache
		if (mMemoryCache != null) {
			mMemoryCache.set(key, drawable);
		}
	}

	/**
	 * Get Bitmap. If not exist in file cache, will try to re-use the file cache of the other sizes.
	 *
	 * If no file cache can be used, download then save to file.
	 */
	public Bitmap fetchBitmapData(ImageTask imageTask, ImageResizer imageResizer) {
		Bitmap bitmap = null;
		if (mFileCache != null) {
			InputStream inputStream = null;

			String fileCacheKey = imageTask.getFileCacheKey();
			ImageReuseInfo reuseInfo = imageTask.getImageReuseInfo();

			if (DEBUG) {
				Log.d(TAG, String.format(MSG_FETCH_BEGIN, imageTask));
				Log.d(TAG, String.format(MSG_FETCH_BEGIN_IDENTITY_KEY, imageTask, imageTask.getIdentityKey()));
				Log.d(TAG, String.format(MSG_FETCH_BEGIN_FILE_CACHE_KEY, imageTask, fileCacheKey));
				Log.d(TAG, String.format(MSG_FETCH_BEGIN_ORIGIN_URL, imageTask, imageTask.getOriginUrl()));
				Log.d(TAG, String.format(MSG_FETCH_BEGIN_IDENTITY_URL, imageTask, imageTask.getIdentityUrl()));
			}

			// read from file cache
			inputStream = mFileCache.read(fileCacheKey);

			// try to reuse
			if (inputStream == null) {
				if (reuseInfo != null && reuseInfo.getReuseSizeList() != null && reuseInfo.getReuseSizeList().length > 0) {
					if (DEBUG) {
						Log.d(TAG, String.format(MSG_FETCH_TRY_REUSE, imageTask));
					}

					final String[] sizeKeyList = reuseInfo.getReuseSizeList();
					for (int i = 0; i < sizeKeyList.length; i++) {
						String size = sizeKeyList[i];
						final String key = imageTask.generateFileCacheKeyForReuse(size);
						inputStream = mFileCache.read(key);

						if (inputStream != null) {
							if (DEBUG) {
								Log.d(TAG, String.format(MSG_FETCH_REUSE_SUCC, imageTask, size));
							}
							break;
						} else {
							if (DEBUG) {
								Log.d(TAG, String.format(MSG_FETCH_REUSE_FAIL, imageTask, size, key));
							}
						}
					}
				}
			} else {
				if (DEBUG) {
					Log.d(TAG, String.format(MSG_FETCH_HIT_DISK_CACHE, imageTask));
				}
			}

			// We've got nothing from file cache
			try {
				if (inputStream == null) {
					String url = imageTask.getRemoteUrl();
					if (DEBUG) {
						Log.d(TAG, String.format(MSG_FETCH_DOWNLOAD, imageTask, url));
					}
					DiskLruCache.Editor editor = mFileCache.open(fileCacheKey);
					if (editor != null) {
						if (downloader.downloadUrlToStream(url, editor.newOutputStream(0))) {
							editor.commit();
						} else {
							editor.abort();
						}
					} else {
						Log.e(TAG, imageTask + " open editor fail. file cache key: " + fileCacheKey);
					}
					inputStream = mFileCache.read(fileCacheKey);
				}
				if (inputStream != null) {
					FileDescriptor fd = ((FileInputStream) inputStream).getFD();
					bitmap = decodeSampledBitmapFromDescriptor(fd, imageTask, imageResizer);
				} else {
					Log.e(TAG, imageTask + " fetch bitmap fail. file cache key: " + fileCacheKey);
				}
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				try {
					if (inputStream != null) {
						inputStream.close();
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		return bitmap;
	}

    public Bitmap fetchBitmapDataUseStream(ImageTask imageTask, ImageResizer imageResizer) {
        try {
            String url = imageTask.getRemoteUrl();
            InputStream in = downloader.getDownLoadInputStream(url);
            if (in == null) {
                return null;
            }
            MarkableInputStream markStream = new MarkableInputStream(in);
            in = markStream;

            long mark = markStream.savePosition(MARKER);

            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, options);

            imageTask.setBitmapOriginSize(options.outWidth, options.outHeight);
            options.inSampleSize = imageResizer.getInSampleSize(imageTask);
            markStream.reset(mark);

            return BitmapFactory.decodeStream(in, null, options);
        }catch (IOException e) {

        }
        return null;
    }


	private Bitmap decodeSampledBitmapFromDescriptor(FileDescriptor fileDescriptor, ImageTask imageTask, ImageResizer imageResizer) {

		// First decode with inJustDecodeBounds=true to check dimensions
		final BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeFileDescriptor(fileDescriptor, null, options);

		imageTask.setBitmapOriginSize(options.outWidth, options.outHeight);

		// Calculate inSampleSize
		options.inSampleSize = imageResizer.getInSampleSize(imageTask);

		// Decode bitmap with inSampleSize set
		options.inJustDecodeBounds = false;

		if (DEBUG) {
			Log.d(TAG, String.format(MSG_DECODE, imageTask, imageTask.getBitmapOriginSize().x, imageTask.getBitmapOriginSize().y, options.inSampleSize));
		}

		Bitmap bitmap = BitmapFactory.decodeFileDescriptor(fileDescriptor, null, options);

		return bitmap;
	}

	public void flushFileCache() {
		if (null != mFileCache) {
			mFileCache.flushDiskCacheAsync();
		}
	}

	/**
	 * clear the memory cache
	 */
	public void clearMemoryCache() {
		if (mMemoryCache != null) {
			mMemoryCache.clear();
		}
	}

	/**
	 * Return the byte usage per pixel of a bitmap based on its configuration.
	 * 
	 * @param config
	 *            The bitmap configuration.
	 * @return The byte usage per pixel.
	 */
	private static int getBytesPerPixel(Config config) {
		if (config == Config.ARGB_8888) {
			return 4;
		} else if (config == Config.RGB_565) {
			return 2;
		} else if (config == Config.ARGB_4444) {
			return 2;
		} else if (config == Config.ALPHA_8) {
			return 1;
		}
		return 1;
	}

	/**
	 * Get the size in bytes of a bitmap in a BitmapDrawable. Note that from Android 4.4 (KitKat) onward this returns the allocated memory size of the bitmap which can be larger than the actual bitmap data byte count (in the case it was re-used).
	 * 
	 * @param value
	 * @return size in bytes
	 */
	@TargetApi(VERSION_CODES.KITKAT)
	public static int getBitmapSize(BitmapDrawable value) {
		Bitmap bitmap = value.getBitmap();

		// From KitKat onward use getAllocationByteCount() as allocated bytes can potentially be
		// larger than bitmap byte count.
		if (Version.hasKitKat()) {
			return bitmap.getAllocationByteCount();
		}

		if (Version.hasHoneycombMR1()) {
			return bitmap.getByteCount();
		}

		// Pre HC-MR1
		return bitmap.getRowBytes() * bitmap.getHeight();
	}
}
